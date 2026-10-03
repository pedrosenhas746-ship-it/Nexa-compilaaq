package com.nexa.xr.importer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Streams all game files unchanged, replacing only manifest/runtime libraries/signatures. */
public final class ApkRewriter {
    public static void rewrite(File input,File output,byte[] manifest,Map<String,File> replacements)throws IOException {
        try(ZipFile apk=new ZipFile(input);CountingOutputStream counter=new CountingOutputStream(new BufferedOutputStream(new FileOutputStream(output)));
            ZipOutputStream target=new ZipOutputStream(counter)) {
            Set<String> seen=new HashSet<>();target.setLevel(1);
            Enumeration<? extends ZipEntry> entries=apk.entries();
            while(entries.hasMoreElements()) {
                ZipEntry e=entries.nextElement();String n=e.getName();
                if(!seen.add(n))throw new IOException("Entrada duplicada no APK: "+n);
                if(signature(n)||replacements.containsKey(n))continue;
                if(n.equals("AndroidManifest.xml")){write(target,counter,n,manifest);continue;}
                ZipEntry out=new ZipEntry(n);out.setTime(946684800000L);
                // Native code is compressed and extracted: no dependence on 4/16 KB mmap alignment.
                if(e.getMethod()==ZipEntry.STORED&&!n.startsWith("lib/")) {
                    out.setMethod(ZipEntry.STORED);out.setSize(e.getSize());out.setCrc(e.getCrc());align(out,counter.count);
                }
                target.putNextEntry(out);
                try(InputStream in=apk.getInputStream(e)){copy(in,target,e.getSize());}
                target.closeEntry();
            }
            for(Map.Entry<String,File> e:replacements.entrySet()) {
                ZipEntry out=new ZipEntry(e.getKey());out.setTime(946684800000L);target.putNextEntry(out);
                try(InputStream in=new FileInputStream(e.getValue())){copy(in,target,e.getValue().length());}target.closeEntry();
            }
        }
    }
    private static boolean signature(String n){String s=n.toUpperCase(Locale.ROOT);return s.equals("STAMP-CERT-SHA256")||(s.startsWith("META-INF/")&&(s.endsWith(".SF")||s.endsWith(".RSA")||s.endsWith(".DSA")||s.endsWith(".EC")||s.equals("META-INF/MANIFEST.MF")));}
    private static void write(ZipOutputStream z,CountingOutputStream c,String n,byte[] b)throws IOException{CRC32 crc=new CRC32();crc.update(b);ZipEntry e=new ZipEntry(n);e.setTime(946684800000L);e.setMethod(ZipEntry.STORED);e.setSize(b.length);e.setCrc(crc.getValue());align(e,c.count);z.putNextEntry(e);z.write(b);z.closeEntry();}
    private static void align(ZipEntry e,long offset){int padding=(int)((4-(offset+30+e.getName().getBytes(StandardCharsets.UTF_8).length)%4)%4);if(padding!=0){byte[]extra=new byte[4+padding];extra[0]=(byte)0xfe;extra[1]=(byte)0xca;extra[2]=(byte)padding;e.setExtra(extra);}}
    public static long copy(InputStream in,OutputStream out,long limit)throws IOException{byte[]buf=new byte[65536];long total=0;for(int n;(n=in.read(buf))!=-1;){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Importação cancelada");if(n==0)continue;total+=n;if(limit>=0&&total>limit)throw new IOException("Tamanho de arquivo inesperado");out.write(buf,0,n);}return total;}
    public static byte[] readBounded(InputStream in,int max)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();copy(in,b,max);return b.toByteArray();}
    private static final class CountingOutputStream extends FilterOutputStream{long count;CountingOutputStream(OutputStream o){super(o);}public void write(int b)throws IOException{out.write(b);count++;}public void write(byte[]b,int off,int len)throws IOException{out.write(b,off,len);count+=len;}}
}
