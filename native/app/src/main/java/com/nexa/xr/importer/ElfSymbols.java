package com.nexa.xr.importer;
import java.io.*;
import java.util.*;

/** Reads actual dynamic symbols without loading untrusted native code. */
public final class ElfSymbols {
    public final Set<String> imports=new TreeSet<>(), exports=new TreeSet<>();
    public static ElfSymbols read(File file,String abi)throws IOException {
        try(RandomAccessFile f=new RandomAccessFile(file,"r")) {
            if(f.length()<64||f.readInt()!=0x7f454c46)throw new IOException("ELF inválido");
            int cls=f.readUnsignedByte(),endian=f.readUnsignedByte();
            if(endian!=1||cls!=(abi.equals("arm64-v8a")?2:1))throw new IOException("ABI ELF incompatível: "+abi);
            f.seek(16);if(le(f,2)!=3||le(f,2)!=(cls==2?183:40))throw new IOException("Tipo/máquina ELF incorretos");
            f.seek(cls==2?40:32);long shoff=le(f,cls==2?8:4);
            f.seek(cls==2?58:46);int shsize=(int)le(f,2),count=(int)le(f,2);
            if(count==0||shsize<(cls==2?64:40)||shoff<0||shoff+(long)shsize*count>f.length())throw new IOException("ELF sem tabela de símbolos auditável");
            ElfSymbols result=new ElfSymbols();
            for(int i=0;i<count;i++) {
                long header=shoff+(long)i*shsize;f.seek(header+4);if(le(f,4)!=11)continue;
                f.seek(header+(cls==2?24:16));long offset=le(f,cls==2?8:4),size=le(f,cls==2?8:4);int link=(int)le(f,4);
                f.seek(header+(cls==2?56:36));long entrySize=le(f,cls==2?8:4);
                if(link<0||link>=count||entrySize<(cls==2?24:16)||size<0||size/entrySize>2000000||offset<0||offset+size>f.length())throw new IOException("Dynsym inválido");
                f.seek(shoff+(long)link*shsize+(cls==2?24:16));long strings=le(f,cls==2?8:4),stringSize=le(f,cls==2?8:4);
                if(strings<0||stringSize<0||strings+stringSize>f.length())throw new IOException("Dynstr inválido");
                for(long n=0;n<size/entrySize;n++) {
                    long sym=offset+n*entrySize;f.seek(sym);long name=le(f,4);
                    f.seek(sym+(cls==2?4:12));int info=f.readUnsignedByte(),visibility=f.readUnsignedByte()&3,section=(int)le(f,2);
                    if(name==0||name>=stringSize||(info>>4)==0)continue;
                    String s=cString(f,strings+name,Math.min(stringSize-name,8192));
                    if(s.contains("vrapi_")||s.startsWith("xr")) {
                        if(section==0)result.imports.add(s);
                        else if(visibility==0||visibility==3)result.exports.add(s);
                    }
                }
            }
            return result;
        }
    }
    private static long le(RandomAccessFile f,int n)throws IOException {long v=0;for(int i=0;i<n;i++)v|=(long)f.readUnsignedByte()<<(8*i);if(v<0)throw new IOException("Offset ELF excessivo");return v;}
    private static String cString(RandomAccessFile f,long p,long max)throws IOException{f.seek(p);ByteArrayOutputStream b=new ByteArrayOutputStream();for(int i=0;i<max;i++){int v=f.readUnsignedByte();if(v==0)return b.toString("UTF-8");b.write(v);}throw new IOException("Símbolo ELF sem terminador");}
}
