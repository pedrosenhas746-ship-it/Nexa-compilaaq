package com.nexa.xr.importer;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.jpountz.lz4.LZ4Factory;

/** Optional, bounded UnityFS settings edit. Never edits scripts, scenes, graphics or licenses. */
public final class UnityOpenXrProfiles {
    private static final int LIMIT=256*1024*1024;
    public static final class Result {
        public final byte[] data;public final List<String> changes;
        Result(byte[] data,List<String> changes){this.data=data;this.changes=changes;}
    }
    private static final class Cursor {
        final ByteBuffer b;
        Cursor(byte[] data,boolean little){b=ByteBuffer.wrap(data).order(little?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN);}
        int i(){return b.getInt();}long l(){long v=b.getLong();if(v<0)throw new IllegalArgumentException("Offset negativo");return v;}
        int u(){return Short.toUnsignedInt(b.getShort());}int bit(){return Byte.toUnsignedInt(b.get());}
        void at(long pos){if(pos<0||pos>b.limit())throw new IllegalArgumentException("Offset fora do arquivo");b.position((int)pos);}
        void skip(int size){at((long)b.position()+size);}void align(int n){at(((long)b.position()+n-1)/n*n);}
        byte[] bytes(int size){if(size<0||size>b.remaining())throw new IllegalArgumentException("Tamanho fora do arquivo");byte[] out=new byte[size];b.get(out);return out;}
        String zero(){ByteArrayOutputStream s=new ByteArrayOutputStream();for(int j=0;j<4096;j++){int c=bit();if(c==0)return new String(s.toByteArray(),StandardCharsets.UTF_8);s.write(c);}throw new IllegalArgumentException("String excessiva");}
        String text(){int n=i();if(n<0||n>4096)throw new IllegalArgumentException("String inválida");String s=new String(bytes(n),StandardCharsets.UTF_8);align(4);return s;}
    }
    private static byte[] inflate(byte[] source,int size,int flags)throws IOException {
        if(size<0||size>LIMIT)throw new IOException("Bundle Unity excessivo");
        if((flags&63)==0){if(source.length!=size)throw new IOException("Bloco Unity truncado");return source;}
        if((flags&63)!=2&&(flags&63)!=3)throw new IOException("Compressão Unity não suportada nesta opção");
        try {byte[] out=new byte[size];int n=LZ4Factory.safeInstance().safeDecompressor().decompress(source,0,source.length,out,0,size);if(n!=size)throw new IOException("Bloco Unity incompleto");return out;}
        catch(RuntimeException e){throw new IOException("Bloco Unity LZ4 inválido",e);}
    }
    public static Result adapt(byte[] source)throws IOException {
        try{return adaptImpl(source);}catch(BufferUnderflowException|IndexOutOfBoundsException|IllegalArgumentException e){throw new IOException("Estrutura Unity não reconhecida; nenhuma alteração foi aplicada",e);}
    }
    private static Result adaptImpl(byte[] source)throws IOException {
        if(source.length>64*1024*1024)throw new IOException("Bundle acima do limite de memória desta opção (64 MB)");
        Cursor h=new Cursor(source,false);
        if(!h.zero().equals("UnityFS"))throw new IOException("Esta opção exige um bundle UnityFS");
        int format=h.i();String player=h.zero(),engine=h.zero();
        if(format!=7||!engine.matches("202[01]\\..*"))throw new IOException("Perfis automáticos: UnityFS 7 / Unity 2020–2021 suportados");
        long total=h.l();int compressed=h.i(),raw=h.i(),flags=h.i();
        if(total!=source.length||(flags&0x40)==0||(flags&~0x2ff)!=0||(flags&0x400)!=0)throw new IOException("Flags/tamanho Unity não suportados");
        h.align(16);int start=h.b.position();
        h.at((flags&0x80)!=0?source.length-(long)compressed:start);
        byte[] info=inflate(h.bytes(compressed),raw,flags);Cursor directory=new Cursor(info,false);directory.skip(16);
        int count=directory.i();if(count<1||count>8192)throw new IOException("Quantidade de blocos Unity inválida");
        int[] unpacked=new int[count],packed=new int[count],blockFlags=new int[count];long expanded=0;
        for(int i=0;i<count;i++){unpacked[i]=directory.i();packed[i]=directory.i();blockFlags[i]=directory.u();if(unpacked[i]<0||unpacked[i]>16*1024*1024||packed[i]<0)throw new IOException("Bloco inválido");expanded+=unpacked[i];if(expanded>LIMIT)throw new IOException("Bundle Unity descompactado excessivo");}
        int files=directory.i();if(files<1||files>4096)throw new IOException("Diretório Unity inválido");
        long targetOffset=-1,targetSize=0;
        for(int i=0;i<files;i++){long offset=directory.l(),size=directory.l();directory.i();String name=directory.zero();if(offset>expanded||size>expanded-offset)throw new IOException("Arquivo Unity fora dos blocos");if(name.equals("globalgamemanagers.assets")){if(targetOffset!=-1)throw new IOException("Settings Unity duplicados");targetOffset=offset;targetSize=size;}}
        if(targetOffset==-1)return new Result(source,Collections.emptyList());
        h.at((flags&0x80)!=0?start:(long)start+compressed);if((flags&0x200)!=0)h.align(16);
        if(targetSize>16*1024*1024)throw new IOException("Settings Unity excessivos");
        byte[] asset=new byte[(int)targetSize];int[] blockOffsets=new int[count];Map<Integer,byte[]> affected=new HashMap<>();long logical=0;
        for(int i=0;i<count;i++) {
            blockOffsets[i]=h.b.position();
            long from=Math.max(logical,targetOffset),to=Math.min(logical+unpacked[i],targetOffset+targetSize);
            if(from<to) {
                byte[] block=inflate(h.bytes(packed[i]),unpacked[i],blockFlags[i]);affected.put(i,block);
                System.arraycopy(block,(int)(from-logical),asset,(int)(from-targetOffset),(int)(to-from));
            }else h.skip(packed[i]);
            logical+=unpacked[i];
        }
        long expectedEnd=(flags&0x80)!=0?source.length-(long)compressed:source.length;
        if(h.b.position()!=expectedEnd)throw new IOException("Bytes extras nos blocos Unity");
        List<String> changes=editAsset(asset);if(changes.isEmpty())return new Result(source,changes);
        byte[] rebuiltInfo=info.clone();Arrays.fill(rebuiltInfo,0,16,(byte)0);ByteBuffer meta=ByteBuffer.wrap(rebuiltInfo).order(ByteOrder.BIG_ENDIAN);
        Map<Integer,byte[]> replacements=new HashMap<>();logical=0;long bytes=0;
        for(int i=0;i<count;i++){
            byte[] block=affected.remove(i);
            if(block!=null){long from=Math.max(logical,targetOffset),to=Math.min(logical+unpacked[i],targetOffset+targetSize);
                System.arraycopy(asset,(int)(from-targetOffset),block,(int)(from-logical),(int)(to-from));
                byte[] encoded=LZ4Factory.safeInstance().fastCompressor().compress(block);replacements.put(i,encoded);
                meta.putInt(20+i*10+4,encoded.length);meta.putShort(20+i*10+8,(short)2);bytes+=encoded.length;
            }else bytes+=packed[i];
            logical+=unpacked[i];
        }
        byte[] compressedInfo=LZ4Factory.safeInstance().fastCompressor().compress(rebuiltInfo);
        ByteArrayOutputStream headerOutput=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(headerOutput);
        zero(out,"UnityFS");out.writeInt(format);zero(out,player);zero(out,engine);int sizePosition=headerOutput.size();out.writeLong(0);
        out.writeInt(compressedInfo.length);out.writeInt(rebuiltInfo.length);out.writeInt(0x42);
        while(headerOutput.size()%16!=0)out.writeByte(0);
        long length=headerOutput.size()+compressedInfo.length+bytes;if(length>LIMIT)throw new IOException("Bundle preparado excessivo");
        byte[] result=new byte[(int)length],newHeader=headerOutput.toByteArray();System.arraycopy(newHeader,0,result,0,newHeader.length);
        int outputOffset=newHeader.length;System.arraycopy(compressedInfo,0,result,outputOffset,compressedInfo.length);outputOffset+=compressedInfo.length;
        for(int i=0;i<count;i++){byte[] replacement=replacements.remove(i);int n=replacement==null?packed[i]:replacement.length;
            System.arraycopy(replacement==null?source:replacement,replacement==null?blockOffsets[i]:0,result,outputOffset,n);outputOffset+=n;}
        ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN).putLong(sizePosition,result.length);
        return new Result(result,changes);
    }
    private static void zero(DataOutputStream out,String value)throws IOException{out.write(value.getBytes(StandardCharsets.UTF_8));out.writeByte(0);}
    private static final class ObjectInfo {final int type,offset,size;ObjectInfo(int t,int o,int s){type=t;offset=o;size=s;}}
    static List<String> editAsset(byte[] data)throws IOException {
        Cursor header=new Cursor(data,false);header.i();int file32=header.i(),version=header.i();long dataOffset=Integer.toUnsignedLong(header.i());int endian=header.bit();header.skip(3);
        if(version<17||version>22||endian!=0)throw new IOException("SerializedFile Unity não suportado");
        long fileSize=Integer.toUnsignedLong(file32);long metadata;
        if(version>=22){metadata=Integer.toUnsignedLong(header.i());fileSize=header.l();dataOffset=header.l();header.l();}
        else metadata=Integer.toUnsignedLong(ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).getInt());
        int headerSize=header.b.position();if(fileSize!=data.length||dataOffset<headerSize||dataOffset>data.length||metadata>data.length-headerSize)throw new IOException("Cabeçalho SerializedFile inválido");
        Cursor r=new Cursor(data,true);r.at(headerSize);String unity=r.zero();r.i();
        if(!unity.matches("202[01]\\..*")||r.bit()!=0)throw new IOException("Settings com árvore de tipos/outra versão: preservados");
        int typeCount=r.i();if(typeCount<1||typeCount>4096)throw new IOException("Tipos Unity inválidos");int[] classes=new int[typeCount];
        for(int i=0;i<typeCount;i++){classes[i]=r.i();r.bit();r.u();if(classes[i]==114)r.skip(16);r.skip(16);}
        int objects=r.i();if(objects<1||objects>200000)throw new IOException("Objetos Unity excessivos");Map<Long,ObjectInfo> table=new HashMap<>();
        for(int i=0;i<objects;i++){
            r.align(4);long path=r.l();long relative=version>=22?r.l():Integer.toUnsignedLong(r.i());long size=Integer.toUnsignedLong(r.i());int type=r.i();
            long offset=dataOffset+relative;
            if(type<0||type>=classes.length||relative>data.length-dataOffset||size>data.length-offset||size>Integer.MAX_VALUE)throw new IOException("Objeto Unity inválido");
            if(table.put(path,new ObjectInfo(classes[type],(int)offset,(int)size))!=null)throw new IOException("PathID Unity duplicado");
        }
        if(r.b.position()>dataOffset||r.b.position()>headerSize+metadata)throw new IOException("Tabela Unity fora dos metadados");
        List<Integer> writes=new ArrayList<>();List<String> changes=new ArrayList<>();boolean hasStandard=false;
        for(ObjectInfo object:table.values()) {
            if(object.type!=114||object.size<36)continue;
            byte[] raw=Arrays.copyOfRange(data,object.offset,object.offset+object.size);Cursor b=new Cursor(raw,true);
            b.skip(16);int scriptFile=b.i();long scriptPath=b.l();String name=b.text();
            if(scriptFile!=0||!name.endsWith(" Android"))continue;
            ObjectInfo script=table.get(scriptPath);if(script==null||script.type!=115)continue;
            Cursor s=new Cursor(Arrays.copyOfRange(data,script.offset,script.offset+script.size),true);s.text();s.i();s.skip(16);String cls=s.text(),namespace=s.text(),assembly=s.text();
            if(!namespace.startsWith("UnityEngine.XR.OpenXR.Features"))continue;
            boolean simple=cls.equals("KHRSimpleControllerProfile")&&assembly.equals("Unity.XR.OpenXR.dll");
            boolean touch=cls.equals("OculusTouchControllerProfile")&&assembly.equals("Unity.XR.OpenXR.dll");
            boolean pico=cls.equals("PICOFeature")&&assembly.equals("Unity.XR.OpenXR.Features.PICOSupport.dll");
            boolean picoInput=cls.equals("PICOTouchControllerProfile")&&assembly.equals("Unity.XR.OpenXRPico.dll");
            if(!simple&&!touch&&!pico&&!picoInput)continue;
            int featureFlag=b.b.position(),enabled=b.bit();if(enabled>1)continue;b.align(4);b.text();String featureVersion=b.text(),id=b.text(),extensions=b.text();b.text();b.i();int required=b.bit();
            if(required!=0||!extensions.isEmpty())continue;
            String expected=simple?"com.unity.openxr.feature.input.khrsimpleprofile":touch?"com.unity.openxr.feature.input.oculustouch":pico?"com.unity.openxr.feature.piconeo3":"com.unity.openxr.feature.input.PICOtouch";
            if(!id.equals(expected)||!featureVersion.equals((pico||picoInput)?"1.0.0":"0.0.1"))continue;
            int value=(simple||touch)?1:0;hasStandard|=simple||touch;
            if(enabled!=value){writes.add(object.offset+featureFlag);changes.add(cls+": "+(value==1?"ativado":"desativado"));}
        }
        // No partial mutations: parse every candidate before writing; require a standard profile.
        if(!hasStandard)return Collections.emptyList();
        for(int i=0;i<writes.size();i++)data[writes.get(i)]=(byte)(changes.get(i).endsWith(": ativado")?1:0);
        return changes;
    }
}
