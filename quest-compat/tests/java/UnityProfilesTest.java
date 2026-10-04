package com.nexa.xr.importer;
import java.io.*;import java.nio.*;import java.util.*;

public final class UnityProfilesTest {
    static byte[] ints(int... values){ByteBuffer b=ByteBuffer.allocate(values.length*4).order(ByteOrder.LITTLE_ENDIAN);for(int v:values)b.putInt(v);return b.array();}
    static byte[] longs(long value){return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();}
    static void text(ByteArrayOutputStream out,String s)throws Exception{byte[] b=s.getBytes("UTF-8");out.write(ints(b.length));out.write(b);while(out.size()%4!=0)out.write(0);}
    static byte[] script(String cls,String assembly)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();text(out,cls);out.write(ints(0));out.write(new byte[16]);text(out,cls);text(out,"UnityEngine.XR.OpenXR.Features.Interactions");text(out,assembly);return out.toByteArray();}
    static byte[] feature(long script,String cls,String id,boolean enabled,boolean required)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[12]);out.write(ints(1,0));out.write(longs(script));text(out,cls+" Android");out.write(ints(enabled?1:0));text(out,cls);text(out,"0.0.1");text(out,id);text(out,"");text(out,"Unity");out.write(ints(0,required?1:0));return out.toByteArray();}
    static byte[] asset(boolean required)throws Exception{
        List<byte[]> objects=Arrays.asList(script("OculusTouchControllerProfile","Unity.XR.OpenXR.dll"),feature(1,"OculusTouchControllerProfile","com.unity.openxr.feature.input.oculustouch",false,required),script("KHRSimpleControllerProfile","Unity.XR.OpenXR.dll"),feature(3,"KHRSimpleControllerProfile","com.unity.openxr.feature.input.khrsimpleprofile",false,false));
        ByteArrayOutputStream meta=new ByteArrayOutputStream();meta.write("2021.1.11f1\0".getBytes("UTF-8"));meta.write(ints(13));meta.write(0);meta.write(ints(2));
        for(int type:new int[]{115,114}){meta.write(ints(type));meta.write(0);meta.write(new byte[]{-1,-1});if(type==114)meta.write(new byte[16]);meta.write(new byte[16]);}
        meta.write(ints(objects.size()));long offset=0;
        for(int i=0;i<objects.size();i++){while((meta.size()+48)%4!=0)meta.write(0);meta.write(longs(i+1));meta.write(longs(offset));meta.write(ints(objects.get(i).length,i%2));offset+=objects.get(i).length;}
        int dataOffset=48+meta.size();byte[] result=new byte[(int)(dataOffset+offset)];ByteBuffer h=ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN);h.putInt(0).putInt(0).putInt(22).putInt(0).putInt(0).putInt(meta.size()).putLong(result.length).putLong(dataOffset).putLong(0);System.arraycopy(meta.toByteArray(),0,result,48,meta.size());int p=dataOffset;for(byte[] object:objects){System.arraycopy(object,0,result,p,object.length);p+=object.length;}return result;
    }
    static byte[] bundle(byte[] asset)throws Exception {
        ByteArrayOutputStream directory=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(directory);d.write(new byte[16]);d.writeInt(1);d.writeInt(asset.length+7);d.writeInt(asset.length+7);d.writeShort(0);d.writeInt(2);
        d.writeLong(0);d.writeLong(asset.length);d.writeInt(4);d.writeBytes("globalgamemanagers.assets\0");d.writeLong(asset.length);d.writeLong(7);d.writeInt(0);d.writeBytes("level0\0");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeBytes("UnityFS\0");out.writeInt(7);out.writeBytes("5.x.x\0");out.writeBytes("2021.1.11f1\0");int sizePos=bytes.size();out.writeLong(0);out.writeInt(directory.size());out.writeInt(directory.size());out.writeInt(0x40);while(bytes.size()%16!=0)out.write(0);out.write(directory.toByteArray());out.write(asset);out.writeBytes("SCENE!!");byte[] result=bytes.toByteArray();ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN).putLong(sizePos,result.length);return result;
    }
    public static void main(String[] args)throws Exception {
        byte[] original=asset(false),copy=original.clone();List<String> changes=UnityOpenXrProfiles.editAsset(copy);
        if(changes.size()!=2)throw new AssertionError(changes);int changed=0;for(int i=0;i<copy.length;i++)if(copy[i]!=original[i]){if(original[i]!=0||copy[i]!=1)throw new AssertionError("unexpected edit");changed++;}if(changed!=2)throw new AssertionError("wrong mutation count");
        if(!UnityOpenXrProfiles.editAsset(copy).isEmpty())throw new AssertionError("not idempotent");
        byte[] required=asset(true);if(UnityOpenXrProfiles.editAsset(required).size()!=1)throw new AssertionError("required feature edited");
        byte[] packed=bundle(original);UnityOpenXrProfiles.Result result=UnityOpenXrProfiles.adapt(packed);
        if(result.changes.size()!=2||!UnityOpenXrProfiles.adapt(result.data).changes.isEmpty())throw new AssertionError("bundle roundtrip failed");
        byte[] bad=Arrays.copyOf(packed,packed.length-1);try{UnityOpenXrProfiles.adapt(bad);throw new AssertionError("truncated bundle accepted");}catch(IOException expected){}
        byte[] malformed=asset(false);ByteBuffer.wrap(malformed).order(ByteOrder.BIG_ENDIAN).putInt(8,99);byte[] before=malformed.clone();try{UnityOpenXrProfiles.editAsset(malformed);throw new AssertionError("version accepted");}catch(IOException expected){}if(!Arrays.equals(before,malformed))throw new AssertionError("partial mutation");
        System.out.println("Unity settings: bounded edits, required features preserved, idempotence and bundle roundtrip verified.");
    }
}
