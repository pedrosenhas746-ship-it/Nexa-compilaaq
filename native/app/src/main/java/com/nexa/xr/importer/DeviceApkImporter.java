package com.nexa.xr.importer;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.math.BigInteger;
import javax.security.auth.x500.X500Principal;
import java.util.*;
import java.util.zip.*;

public final class DeviceApkImporter {
    public interface Progress { void update(String text); }
    public static final class Analysis {
        public String packageName,route,abi;
        public final List<String> warnings=new ArrayList<>();
        public final Set<String> abis=new LinkedHashSet<>();
        public final Map<String,File> replacements=new LinkedHashMap<>();
        public byte[] manifest;
        public int imports;
        public String summary(){return packageName+"\n"+route+" • "+abi+"\n"+imports+" símbolos VrApi verificados\n\n"+String.join("\n\n",warnings);}
    }
    private final Context context;
    private final File folder;
    public DeviceApkImporter(Context context,File folder){this.context=context;this.folder=folder;}
    public Analysis inspect(File input,Progress progress)throws Exception {
        Analysis a=new Analysis();
        try(ZipFile apk=new ZipFile(input)) {
            Set<String> names=new HashSet<>();long expanded=0;
            Enumeration<? extends ZipEntry> list=apk.entries();
            while(list.hasMoreElements()){
                ZipEntry e=list.nextElement();String n=e.getName();
                if(!names.add(n)||n.startsWith("/")||n.contains("\\")||Arrays.asList(n.split("/")).contains(".."))throw new IOException("APK com entradas inválidas ou duplicadas");
                if(e.getSize()<0||e.getSize()>8L*1024*1024*1024)throw new IOException("Arquivo interno excessivo");
                expanded+=e.getSize();if(expanded>16L*1024*1024*1024)throw new IOException("APK descompactado excessivo");
                if(n.startsWith("lib/")&&n.endsWith(".so")){String[]parts=n.split("/");if(parts.length!=3)throw new IOException("Caminho de biblioteca inválido");a.abis.add(parts[1]);}
            }
            ZipEntry entry=apk.getEntry("AndroidManifest.xml");if(entry==null)throw new IOException("Selecione um APK completo, não ZIP/APKS/XAPK");
            byte[] manifest;try(InputStream in=apk.getInputStream(entry)){manifest=ApkRewriter.readBounded(in,16*1024*1024);}
            BinaryManifest xml=new BinaryManifest(manifest);xml.ensureStandalone();a.packageName=xml.packageName();
            if(a.packageName==null||!a.packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))throw new IOException("Identificador Android inválido");
            if(a.packageName.equals(context.getPackageName())||a.packageName.equals("com.oculus.systemdriver")||a.packageName.startsWith("org.freedesktop.monado."))throw new IOException("Selecione um jogo, não o NEXA/driver/runtime");
            PackageInfo info=context.getPackageManager().getPackageArchiveInfo(input.getPath(),0);
            if(info==null||info.applicationInfo==null)throw new IOException("O Android não conseguiu ler este APK");
            if(info.applicationInfo.minSdkVersion>Build.VERSION.SDK_INT)throw new IOException("O jogo exige Android API "+info.applicationInfo.minSdkVersion+"; celular tem "+Build.VERSION.SDK_INT);
            if((Build.VERSION.SDK_INT>=35&&info.applicationInfo.targetSdkVersion<24)||(Build.VERSION.SDK_INT>=34&&info.applicationInfo.targetSdkVersion<23))throw new IOException("O Android deste celular bloqueia o targetSdk antigo deste jogo");
            for(String abi:Build.SUPPORTED_ABIS)if(a.abis.contains(abi)){a.abi=abi;break;}
            if(a.abi==null)throw new IOException("ABI incompatível: APK "+a.abis+" / celular "+Arrays.toString(Build.SUPPORTED_ABIS));
            if(!a.abi.equals("arm64-v8a")&&!a.abi.equals("armeabi-v7a"))throw new IOException("Esta versão oferece runtime ARM32/ARM64");
            for(String abi:a.abis)if(!abi.equals("arm64-v8a")&&!abi.equals("armeabi-v7a"))throw new IOException("APK multi-arquitetura contém ABI sem adaptador: "+abi);
            boolean vrapi=names.contains("lib/"+a.abi+"/libvrapi.so"),openxr=names.contains("lib/"+a.abi+"/libopenxr_loader.so");
            if(!vrapi&&!openxr)throw new IOException("Nenhum loader VrApi/OpenXR separado encontrado. Runtime embutido exige análise específica");
            a.route=vrapi?"VrApi → PhoneXR (experimental)":"OpenXR → PhoneXR (experimental)";
            for(String abi:a.abis) {
                boolean hasVr=names.contains("lib/"+abi+"/libvrapi.so"),hasXr=names.contains("lib/"+abi+"/libopenxr_loader.so");
                if(hasVr!=vrapi||(!vrapi&&!hasXr))throw new IOException("APIs diferentes entre ABIs: preparação específica necessária");
                Set<String> required=new TreeSet<>();
                File temp=new File(folder,"elf-audit.so");
                for(String n:names)if(n.startsWith("lib/"+abi+"/")&&n.endsWith(".so")&&!n.endsWith("/libvrapi.so")&&!n.endsWith("/libopenxr_loader.so")) {
                    progress.update("Analisando "+n.substring(n.lastIndexOf('/')+1)+" • "+abi);
                    try(InputStream in=apk.getInputStream(apk.getEntry(n));OutputStream out=new BufferedOutputStream(new FileOutputStream(temp))){ApkRewriter.copy(in,out,apk.getEntry(n).getSize());}
                    for(String s:ElfSymbols.read(temp,abi).imports)if(s.contains("vrapi_"))required.add(s);
                }
                temp.delete();
                if(vrapi){File lib=asset(abi,"libvrapi.so");Set<String>missing=new TreeSet<>(required);missing.removeAll(ElfSymbols.read(lib,abi).exports);if(!missing.isEmpty())throw new IOException("VrApi ainda sem funções: "+missing);a.replacements.put("lib/"+abi+"/libvrapi.so",lib);a.imports+=required.size();}
                a.replacements.put("lib/"+abi+"/libopenxr_loader.so",asset(abi,"libopenxr_loader.so"));
            }
            a.manifest=xml.adapt();
            try{context.getPackageManager().getPackageInfo(a.packageName,0);a.warnings.add("Já existe uma instalação com este pacote. Uma assinatura diferente impede atualizar. Use um perfil/aparelho de teste para preservar o original e seus saves.");}catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
            a.warnings.add("A adaptação não garante que o jogo abra: imagem, desempenho e controles precisam de teste. Vulkan no caminho VrApi, hand tracking nativo e serviços Meta ainda não estão implementados.");
            a.warnings.add("Arquivos OBB, dados externos e splits não são importados nesta versão. Se o jogo depende deles, o APK sozinho não basta.");
            a.warnings.add("A cópia será assinada por uma chave local do NEXA. Assets e DEX são preservados. Validação de licença e serviços online continuam sujeitos às exigências do jogo.");
            return a;
        }
    }
    private File asset(String abi,String name)throws IOException {
        File f=new File(folder,abi+"-"+name);
        try(InputStream in=context.getAssets().open("quest-runtime/"+abi+"/"+name);OutputStream out=new BufferedOutputStream(new FileOutputStream(f))){ApkRewriter.copy(in,out,16*1024*1024);}
        ElfSymbols.read(f,abi);return f;
    }
    public File prepare(File input,Analysis a,Progress progress)throws Exception {
        File unsigned=new File(folder,"unsigned.apk"),signed=new File(folder,"prepared.apk");
        try {
            progress.update("Preparando uma cópia do jogo…");ApkRewriter.rewrite(input,unsigned,a.manifest,a.replacements);
            progress.update("Assinando no celular…");
            KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
            String alias="nexa-apk-import-v1";
            if(!store.containsAlias(alias)) {
                KeyPairGenerator gen=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore");
                gen.initialize(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                        .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA512)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setCertificateSubject(new X500Principal("CN=NEXA Local APK Import"))
                        .setCertificateSerialNumber(BigInteger.ONE).setCertificateNotBefore(new Date(1577836800000L))
                        .setCertificateNotAfter(new Date(2208988800000L)).build());gen.generateKeyPair();
            }
            PrivateKey key=(PrivateKey)store.getKey(alias,null);X509Certificate certificate=(X509Certificate)store.getCertificate(alias);
            ApkSigner.SignerConfig signer=new ApkSigner.SignerConfig.Builder("nexa-local",key,Collections.singletonList(certificate)).build();
            new ApkSigner.Builder(Collections.singletonList(signer)).setInputApk(unsigned).setOutputApk(signed)
                    .setMinSdkVersion(24).setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(false).setV4SigningEnabled(false)
                    .setOtherSignersSignaturesPreserved(false).build().sign();
            progress.update("Verificando assinatura e cópia…");
            ApkVerifier.Result result=new ApkVerifier.Builder(signed).setMinCheckedPlatformVersion(24).build().verify();
            if(!result.isVerified())throw new IOException("A assinatura não passou na verificação: "+result.getErrors());
            verifyCopies(input,signed,a.replacements.keySet());
            return signed;
        } catch(Exception e){signed.delete();throw e;}finally{unsigned.delete();}
    }
    private static void verifyCopies(File original,File adapted,Set<String> replaced)throws Exception {
        try(ZipFile before=new ZipFile(original);ZipFile after=new ZipFile(adapted)){
            Enumeration<? extends ZipEntry> list=before.entries();
            while(list.hasMoreElements()){
                ZipEntry e=list.nextElement();String n=e.getName();
                if(e.isDirectory()||n.equals("AndroidManifest.xml")||replaced.contains(n)||n.toUpperCase(Locale.ROOT).startsWith("META-INF/")||n.equals("stamp-cert-sha256"))continue;
                ZipEntry target=after.getEntry(n);if(target==null)throw new IOException("Arquivo ausente: "+n);
                if(!Arrays.equals(digest(before.getInputStream(e)),digest(after.getInputStream(target))))throw new IOException("Arquivo alterado: "+n);
            }
        }
    }
    private static byte[] digest(InputStream input)throws Exception{try(InputStream in=input){MessageDigest d=MessageDigest.getInstance("SHA-256");byte[]b=new byte[65536];for(int n;(n=in.read(b))!=-1;)d.update(b,0,n);return d.digest();}}
}
