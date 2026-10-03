import com.nexa.xr.importer.*;
import com.android.apksig.*;
import java.io.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.*;

/** Exercises the same Java manifest/ELF/ZIP code shipped in the Android importer. */
public final class ImporterHostTest {
    public static void main(String[] args)throws Exception {
        File source=new File(args[0]),kit=new File(args[1]),output=new File(args[2]),keyFile=new File(args[3]);
        File dir=new File(output.getParentFile(),"java-import-assets");dir.mkdirs();
        Map<String,File> replacements=new LinkedHashMap<>();byte[] original,patched;int audited=0;
        try(ZipFile apk=new ZipFile(source);ZipFile libs=new ZipFile(kit)) {
            original=ApkRewriter.readBounded(apk.getInputStream(apk.getEntry("AndroidManifest.xml")),16*1024*1024);
            BinaryManifest xml=new BinaryManifest(original);String pkg=xml.packageName();xml.ensureStandalone();patched=xml.adapt();
            if(!pkg.equals(new BinaryManifest(patched).packageName()))throw new AssertionError("package altered");
            byte[] twice=new BinaryManifest(patched).adapt();
            if(!Arrays.equals(patched,twice))throw new AssertionError("manifest edit not idempotent");
            try{new BinaryManifest(new byte[8]);throw new AssertionError("malformed XML accepted");}catch(IOException expected){}
            for(String abi:new String[]{"arm64-v8a","armeabi-v7a"}){
                if(apk.getEntry("lib/"+abi+"/libvrapi.so")==null)continue;
                for(String name:new String[]{"libvrapi.so","libopenxr_loader.so"}){
                    File lib=new File(dir,abi+"-"+name);try(InputStream in=libs.getInputStream(libs.getEntry("lib/"+abi+"/"+name));OutputStream out=new FileOutputStream(lib)){ApkRewriter.copy(in,out,16*1024*1024);}
                    replacements.put("lib/"+abi+"/"+name,lib);
                }
                Set<String>exports=ElfSymbols.read(replacements.get("lib/"+abi+"/libvrapi.so"),abi).exports;
                if(!exports.contains("vrapi_Initialize"))throw new AssertionError("no actual exports");
                try{ElfSymbols.read(replacements.get("lib/"+abi+"/libvrapi.so"),abi.equals("arm64-v8a")?"armeabi-v7a":"arm64-v8a");throw new AssertionError("wrong ELF machine accepted");}catch(IOException expected){}
                Enumeration<? extends ZipEntry> list=apk.entries();
                while(list.hasMoreElements()){
                    ZipEntry e=list.nextElement();if(!e.getName().startsWith("lib/"+abi+"/")||!e.getName().endsWith(".so")||e.getName().endsWith("libvrapi.so")||e.getName().endsWith("libopenxr_loader.so"))continue;
                    File f=new File(dir,"audit.so");try(InputStream in=apk.getInputStream(e);OutputStream out=new FileOutputStream(f)){ApkRewriter.copy(in,out,e.getSize());}
                    Set<String>required=new TreeSet<>();for(String s:ElfSymbols.read(f,abi).imports)if(s.contains("vrapi_"))required.add(s);
                    Set<String>missing=new TreeSet<>(required);missing.removeAll(exports);if(!missing.isEmpty())throw new AssertionError("missing imports: "+missing);audited+=required.size();
                }
            }
        }
        if(replacements.isEmpty())throw new AssertionError("fixture has no VrApi ABI");
        File unsigned=new File(output.getParentFile(),"java-import-unsigned.apk");ApkRewriter.rewrite(source,unsigned,patched,replacements);
        KeyStore ks=KeyStore.getInstance("JKS");try(InputStream in=new FileInputStream(keyFile)){ks.load(in,"android".toCharArray());}
        PrivateKey key=(PrivateKey)ks.getKey("androiddebugkey","android".toCharArray());X509Certificate cert=(X509Certificate)ks.getCertificate("androiddebugkey");
        ApkSigner.SignerConfig signer=new ApkSigner.SignerConfig.Builder("fixture",key,Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(signer)).setInputApk(unsigned).setOutputApk(output).setMinSdkVersion(24)
            .setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(false).setV4SigningEnabled(false).build().sign();
        if(!new ApkVerifier.Builder(output).setMinCheckedPlatformVersion(24).build().verify().isVerified())throw new AssertionError("bad signature");
        int preserved=0;
        try(ZipFile before=new ZipFile(source);ZipFile after=new ZipFile(output)){
            Enumeration<? extends ZipEntry>list=before.entries();
            while(list.hasMoreElements()){
                ZipEntry e=list.nextElement();String n=e.getName();if(n.equals("AndroidManifest.xml")||replacements.containsKey(n)||n.startsWith("META-INF/")||n.equals("stamp-cert-sha256")||e.isDirectory())continue;
                ZipEntry target=after.getEntry(n);if(target==null||!Arrays.equals(hash(before.getInputStream(e)),hash(after.getInputStream(target))))throw new AssertionError("changed entry "+n);preserved++;
            }
            for(Map.Entry<String,File>e:replacements.entrySet())if(!Arrays.equals(hash(new FileInputStream(e.getValue())),hash(after.getInputStream(after.getEntry(e.getKey())))))throw new AssertionError("changed replacement");
        }
        unsigned.delete();System.out.println("Java importer: manifest idempotent; "+audited+" VrApi imports resolved; "+preserved+" entries preserved; v2 signature verified. No game execution.");
    }
    private static byte[]hash(InputStream input)throws Exception{try(InputStream in=input){MessageDigest d=MessageDigest.getInstance("SHA-256");byte[]b=new byte[65536];for(int n;(n=in.read(b))!=-1;)d.update(b,0,n);return d.digest();}}
}
