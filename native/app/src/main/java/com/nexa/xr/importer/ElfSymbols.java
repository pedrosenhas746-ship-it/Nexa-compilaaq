package com.nexa.xr.importer;
import java.io.*;
import java.util.*;

/** Audits native symbols without executing libraries; supports stripped section headers. */
public final class ElfSymbols {
    public final Set<String> imports=new TreeSet<>(), exports=new TreeSet<>();
    private static final int MAX_SYMBOLS=2000000;
    public static ElfSymbols read(File file,String abi)throws IOException {
        try(RandomAccessFile f=new RandomAccessFile(file,"r")) {
            if(f.length()<20)throw new IOException("ELF truncado: "+f.length()+" bytes");
            int magic=f.readInt();
            if(magic!=0x7f454c46)throw new IOException(String.format(Locale.ROOT,"ELF inválido: cabeçalho %08x, tamanho %d bytes",magic,f.length()));
            int cls=f.readUnsignedByte(),endian=f.readUnsignedByte();
            if(endian!=1||cls!=(abi.equals("arm64-v8a")?2:1))throw new IOException("ABI ELF incompatível: "+abi);
            bounds(f,0,cls==2?64:52);
            f.seek(16);if(le(f,2)!=3||le(f,2)!=(cls==2?183:40))throw new IOException("Tipo/máquina ELF incorretos");
            f.seek(cls==2?40:32);long shoff=le(f,cls==2?8:4);
            f.seek(cls==2?58:46);int shsize=(int)le(f,2),count=(int)le(f,2);
            ElfSymbols result=new ElfSymbols();
            if(count!=0&&shoff!=0) {
                if(shsize<(cls==2?64:40))throw new IOException("Tamanho da seção ELF inválido");
                bounds(f,shoff,(long)shsize*count);
                boolean found=false;
                for(int i=0;i<count;i++) {
                    long header=shoff+(long)i*shsize;f.seek(header+4);if(le(f,4)!=11)continue;
                    f.seek(header+(cls==2?24:16));long offset=le(f,cls==2?8:4),size=le(f,cls==2?8:4);int link=(int)le(f,4);
                    f.seek(header+(cls==2?56:36));long entry=le(f,cls==2?8:4);
                    if(link<0||link>=count||entry<(cls==2?24:16)||size%entry!=0)throw new IOException("Dynsym inválido");
                    f.seek(shoff+(long)link*shsize+4);if(le(f,4)!=3)throw new IOException("Dynstr sem STRTAB");
                    f.seek(shoff+(long)link*shsize+(cls==2?24:16));long strings=le(f,cls==2?8:4),stringSize=le(f,cls==2?8:4);
                    readSymbols(f,cls,offset,size/entry,entry,strings,stringSize,result);found=true;
                }
                if(found)return result;
            }
            readDynamic(f,cls,result);return result;
        } catch(EOFException e){throw new IOException("ELF truncado durante leitura",e);}
    }
    private static final class Segment {long address,offset,size;Segment(long a,long o,long s){address=a;offset=o;size=s;}}
    private static long map(RandomAccessFile f,List<Segment> segments,long address,long size)throws IOException {
        for(Segment s:segments)if(address>=s.address&&address-s.address<=s.size&&size<=s.size-(address-s.address)) {
            long offset=s.offset+(address-s.address);bounds(f,offset,size);return offset;
        }
        throw new IOException("Endereço dinâmico fora de PT_LOAD");
    }
    private static void readDynamic(RandomAccessFile f,int cls,ElfSymbols result)throws IOException {
        f.seek(cls==2?32:28);long phoff=le(f,cls==2?8:4);
        f.seek(cls==2?54:42);int entry=(int)le(f,2),count=(int)le(f,2);
        if(count==0||entry<(cls==2?56:32))throw new IOException("ELF sem PT_DYNAMIC auditável");
        bounds(f,phoff,(long)entry*count);List<Segment> loads=new ArrayList<>();long dynamic=0,dynamicSize=0;
        for(int i=0;i<count;i++) {
            long pos=phoff+(long)i*entry;f.seek(pos);long type=le(f,4);
            f.seek(pos+(cls==2?8:4));long offset=le(f,cls==2?8:4),address=le(f,cls==2?8:4);
            f.seek(pos+(cls==2?32:16));long size=le(f,cls==2?8:4);bounds(f,offset,size);
            if(type==1)loads.add(new Segment(address,offset,size));
            if(type==2){if(dynamicSize!=0)throw new IOException("PT_DYNAMIC duplicado");dynamic=offset;dynamicSize=size;}
        }
        int word=cls==2?8:4;if(dynamicSize==0||dynamicSize%(2*word)!=0)throw new IOException("PT_DYNAMIC ausente/inválido");
        Map<Long,Long> tags=new HashMap<>();boolean terminated=false;
        for(long pos=dynamic;pos<dynamic+dynamicSize;pos+=2*word){f.seek(pos);long tag=le(f,word),value=le(f,word);if(tag==0){terminated=true;break;}tags.put(tag,value);}
        if(!terminated||!tags.containsKey(6L)||!tags.containsKey(5L)||!tags.containsKey(10L)||!tags.containsKey(11L))throw new IOException("Tabela dinâmica incompleta");
        long symbolSize=tags.get(11L),stringsSize=tags.get(10L);
        if(symbolSize!=(cls==2?24:16))throw new IOException("DT_SYMENT inválido");
        long symbols=map(f,loads,tags.get(6L),symbolSize),strings=map(f,loads,tags.get(5L),stringsSize),symbolCount;
        if(tags.containsKey(4L)) {
            long hash=map(f,loads,tags.get(4L),8);f.seek(hash+4);symbolCount=le(f,4);
        }else if(tags.containsKey(0x6ffffef5L)) {
            long address=tags.get(0x6ffffef5L),hash=map(f,loads,address,16);f.seek(hash);
            long buckets=le(f,4),first=le(f,4),bloom=le(f,4);le(f,4);
            if(buckets>MAX_SYMBOLS||first>MAX_SYMBOLS||bloom>MAX_SYMBOLS||bloom==0)throw new IOException("GNU hash inválido");
            long bucketAddress=address+16+bloom*word,bucketOffset=map(f,loads,bucketAddress,buckets*4);symbolCount=first;
            long largest=0;for(long i=0;i<buckets;i++){f.seek(bucketOffset+i*4);long index=le(f,4);if(index!=0&&index<first)throw new IOException("GNU bucket inválido");largest=Math.max(largest,index);}
            if(largest!=0){if(largest>=MAX_SYMBOLS)throw new IOException("GNU símbolos excessivos");long chainAddress=bucketAddress+buckets*4;
                for(long index=largest;index<MAX_SYMBOLS;index++) {
                    long offset=map(f,loads,chainAddress+(index-first)*4,4);f.seek(offset);long value=le(f,4);
                    if((value&1)!=0){symbolCount=index+1;break;}
                    if(index==MAX_SYMBOLS-1)throw new IOException("GNU hash sem terminador");
                }
            }
        }else throw new IOException("ELF sem hash para limitar os símbolos dinâmicos");
        map(f,loads,tags.get(6L),symbolCount*symbolSize);
        readSymbols(f,cls,symbols,symbolCount,symbolSize,strings,stringsSize,result);
    }
    private static void readSymbols(RandomAccessFile f,int cls,long offset,long count,long entry,long strings,long stringSize,ElfSymbols result)throws IOException {
        if(count<0||count>MAX_SYMBOLS)throw new IOException("Quantidade de símbolos excessiva");
        bounds(f,offset,count*entry);bounds(f,strings,stringSize);
        for(long n=0;n<count;n++) {
            long sym=offset+n*entry;f.seek(sym);long name=le(f,4);
            f.seek(sym+(cls==2?4:12));int info=f.readUnsignedByte(),visibility=f.readUnsignedByte()&3,section=(int)le(f,2);
            if(name==0||(info>>4)==0)continue;
            if(name>=stringSize)throw new IOException("Nome de símbolo fora de DT_STRTAB");
            String s=cString(f,strings+name,Math.min(stringSize-name,8192));
            if(s.contains("vrapi_")||s.matches("xr[A-Z].*")) {
                if(section==0)result.imports.add(s);else if(visibility==0||visibility==3)result.exports.add(s);
            }
        }
    }
    private static void bounds(RandomAccessFile f,long offset,long size)throws IOException {if(offset<0||size<0||offset>f.length()||size>f.length()-offset)throw new IOException("Dados ELF fora do arquivo");}
    private static long le(RandomAccessFile f,int n)throws IOException {long v=0;for(int i=0;i<n;i++)v|=(long)f.readUnsignedByte()<<(8*i);if(v<0)throw new IOException("Offset ELF excessivo");return v;}
    private static String cString(RandomAccessFile f,long p,long max)throws IOException {f.seek(p);ByteArrayOutputStream b=new ByteArrayOutputStream();for(int i=0;i<max;i++){int v=f.readUnsignedByte();if(v==0)return b.toString("UTF-8");b.write(v);}throw new IOException("Símbolo ELF sem terminador");}
}
