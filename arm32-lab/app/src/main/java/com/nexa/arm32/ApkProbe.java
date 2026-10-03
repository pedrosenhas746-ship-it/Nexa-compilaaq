// SPDX-License-Identifier: GPL-2.0-only
package com.nexa.arm32;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Only extracts selected libraries under generated filenames. Never installs an APK. */
public final class ApkProbe {
    public static String inspect(File apk,File directory)throws IOException {
        StringBuilder out=new StringBuilder();out.append(NativeProbe.selfTest()).append('\n');
        try(ZipFile zip=new ZipFile(apk)) {
            Set<String> names=new HashSet<>(),abis=new TreeSet<>();List<String> libs=new ArrayList<>();
            Enumeration<? extends ZipEntry> entries=zip.entries();int count=0;
            while(entries.hasMoreElements()) {
                if(++count>100000)throw new IOException("APK com entradas demais");
                ZipEntry e=entries.nextElement();String n=e.getName();
                if(!names.add(n)||n.startsWith("/")||n.contains("\\")||Arrays.asList(n.split("/")).contains(".."))throw new IOException("Caminho inválido ou duplicado no APK");
                if(n.startsWith("lib/")&&n.endsWith(".so")) {
                    String[] p=n.split("/");if(p.length!=3)throw new IOException("Caminho de biblioteca inválido");
                    abis.add(p[1]);if(p[1].equals("armeabi-v7a"))libs.add(p[2]);
                }
            }
            if(!names.contains("AndroidManifest.xml"))throw new IOException("Selecione um APK, não ZIP/XAPK");
            out.append("Arquiteturas no APK: ").append(abis).append("\nBibliotecas ARM32: ").append(libs.size()).append('\n');
            if(libs.isEmpty())throw new IOException("Este APK não contém bibliotecas armeabi-v7a");
            List<String> chosen=new ArrayList<>();
            if(libs.contains("libopus_egpv.so"))chosen.add("libopus_egpv.so");
            if(libs.contains("libmain.so"))chosen.add("libmain.so");
            if(chosen.isEmpty())chosen.add(libs.get(0));
            for(int i=0;i<chosen.size();i++) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Teste cancelado");
                String name=chosen.get(i);File output=new File(directory,"probe-"+i+".elf");
                ZipEntry e=zip.getEntry("lib/armeabi-v7a/"+name);
                if(e.getSize()<52||e.getSize()>128L*1024*1024)throw new IOException("Biblioteca acima do limite de 128 MiB");
                try {
                    try(InputStream in=zip.getInputStream(e);OutputStream dest=new FileOutputStream(output)){copy(in,dest,128L*1024*1024);}
                    out.append('\n').append(name).append(":\n").append(NativeProbe.inspect(output.getPath(),name.equals("libmain.so")));
                }finally{output.delete();}
            }
        }
        return out.append("\nO jogo ainda não foi iniciado. Faltam as pontes JNI, bibliotecas Android, threads, gráficos e VR.\nEste resultado confirma somente execução limitada de CPU/ELF ARM32.").toString();
    }
    static void copy(InputStream in,OutputStream out,long max)throws IOException {
        byte[] buffer=new byte[65536];long total=0;int n;
        while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Teste cancelado");total+=n;if(total>max)throw new IOException("Arquivo excede limite de tamanho");out.write(buffer,0,n);}
    }
}
