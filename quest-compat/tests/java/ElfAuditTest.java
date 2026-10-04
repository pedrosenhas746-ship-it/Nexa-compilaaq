import com.nexa.xr.importer.ElfSymbols;import java.io.*;import java.nio.*;import java.nio.file.*;import java.util.*;
public class ElfAuditTest {
    static byte[] library(boolean gnu){
        byte[] out=new byte[1024];ByteBuffer b=ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);b.put(new byte[]{127,69,76,70,2,1,1});b.position(16);b.putShort((short)3).putShort((short)183).putInt(1).putLong(0).putLong(64).putLong(0);b.position(52);b.putShort((short)64).putShort((short)56).putShort((short)2).putShort((short)64).putShort((short)0);
        b.position(64);b.putInt(1).putInt(4).putLong(0).putLong(0x1000).putLong(0).putLong(1024).putLong(1024).putLong(4096);
        b.position(120);b.putInt(2).putInt(4).putLong(256).putLong(0x1100).putLong(0).putLong(96).putLong(96).putLong(8);
        b.position(256);for(long[] tag:new long[][]{{5,0x1300},{6,0x1200},{10,64},{11,24},{gnu?0x6ffffef5L:4,0x1380},{0,0}})b.putLong(tag[0]).putLong(tag[1]);
        b.position(536);b.putInt(1).put((byte)18).put((byte)0).putShort((short)0).putLong(0).putLong(0);
        b.putInt(19).put((byte)18).put((byte)0).putShort((short)1).putLong(0x1450).putLong(8);
        b.position(768);b.put("\0vrapi_Initialize\0\0xrCreateInstance\0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        b.position(896);if(gnu)b.putInt(1).putInt(1).putInt(1).putInt(0).putLong(0).putInt(1).putInt(2).putInt(3);else b.putInt(1).putInt(3).putInt(1).putInt(0).putInt(2).putInt(0);
        return out;
    }
    public static void main(String[] args)throws Exception {File f=File.createTempFile("elf-audit-",".so");try{
        for(boolean gnu:new boolean[]{false,true}){Files.write(f.toPath(),library(gnu));ElfSymbols s=ElfSymbols.read(f,"arm64-v8a");if(!s.imports.contains("vrapi_Initialize")||!s.exports.contains("xrCreateInstance"))throw new AssertionError("stripped dynamic audit failed "+gnu);
            try{ElfSymbols.read(f,"armeabi-v7a");throw new AssertionError("ABI accepted");}catch(IOException expected){}}
        byte[] broken=library(true);ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putLong(328,0x2000);Files.write(f.toPath(),broken);try{ElfSymbols.read(f,"arm64-v8a");throw new AssertionError("bad hash accepted");}catch(IOException expected){}
        Files.write(f.toPath(),new byte[]{127,69,76,70});try{ElfSymbols.read(f,"arm64-v8a");throw new AssertionError("truncation accepted");}catch(IOException expected){}
        System.out.println("ELF: SysV/GNU hash, stripped section tables, ABI and invalid offsets verified.");
    }finally{f.delete();}}
}
