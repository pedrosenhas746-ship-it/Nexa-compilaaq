// SPDX-License-Identifier: GPL-2.0-only
package com.nexa.arm32;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.util.zip.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ProbeTest {
    private File asset(String name)throws IOException {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File f=new File(c.getCacheDir(),name);
        try(InputStream in=c.getAssets().open(name);OutputStream out=new FileOutputStream(f)){ApkProbe.copy(in,out,1024*1024);}
        return f;
    }
    @Test public void cpuExecutesArmThumbMemoryAndStopsLoop(){String r=NativeProbe.selfTest();assertTrue(r.contains("ARM=42; Thumb=12; memória=42; loop interrompido"));assertTrue(r.contains("NEON=OK"));}
    @Test public void loadsRelocatedElfAndExecutesFunction()throws Exception {File f=asset("fixture.elf");try{assertTrue(NativeProbe.inspect(f.getPath(),false).contains("Código real do APK executado: nexa fixture"));}finally{f.delete();}}
    @Test public void jniStopsAtExplicitMissingBridge()throws Exception {File f=asset("fixture.elf");try{assertTrue(NativeProbe.inspect(f.getPath(),true).contains("Ponte pendente: JavaVM.GetEnv"));}finally{f.delete();}}
    @Test public void malformedElfRejected()throws Exception {File f=asset("truncated.elf");try{assertTrue(NativeProbe.inspect(f.getPath(),false).startsWith("FALHA ELF:"));}finally{f.delete();}}
    @Test public void wrongArchitectureRejected()throws Exception {File f=asset("wrong-class.elf");try{assertTrue(NativeProbe.inspect(f.getPath(),false).contains("Esperado ELF32 ARM"));}finally{f.delete();}}
    @Test public void apkPipelineRunsGuestAndReportsJniBlocker()throws Exception {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File apk=new File(c.getCacheDir(),"probe-fixture.apk");
        try{
            try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(apk))){
                out.putNextEntry(new ZipEntry("AndroidManifest.xml"));out.write(0);out.closeEntry();
                for(String name:new String[]{"libopus_egpv.so","libmain.so"}){
                    out.putNextEntry(new ZipEntry("lib/armeabi-v7a/"+name));try(InputStream in=c.getAssets().open("fixture.elf")){ApkProbe.copy(in,out,1024*1024);}out.closeEntry();
                }
            }
            String r=ApkProbe.inspect(apk,c.getCacheDir());assertTrue(r.contains("nexa fixture"));assertTrue(r.contains("Ponte pendente: JavaVM.GetEnv"));assertTrue(r.contains("O jogo ainda não foi iniciado"));
        }finally{apk.delete();}
    }
    @Test public void menuStartsWithoutNativeInitialization(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Activity a=InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(c,LabActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();assertFalse(a.isFinishing());assertTrue(a.getWindow().getDecorView().isAttachedToWindow());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(a::finish);
    }
}
