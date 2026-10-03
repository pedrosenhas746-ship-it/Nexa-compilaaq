package com.nexa.xr.importer;
import android.content.Context;
import android.content.pm.PackageInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.zip.*;

public final class DeviceSigningTest {
    @Test public void preparesVerifiableCopyWithAndroidKeyStore()throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir=new File(context.getCacheDir(),"device-import-test");dir.mkdirs();
        File source=new File(context.getApplicationInfo().sourceDir);
        DeviceApkImporter.Analysis analysis=new DeviceApkImporter.Analysis();
        try(ZipFile apk=new ZipFile(source);InputStream in=apk.getInputStream(apk.getEntry("AndroidManifest.xml"))){
            BinaryManifest xml=new BinaryManifest(ApkRewriter.readBounded(in,16*1024*1024));
            analysis.packageName=xml.packageName();analysis.manifest=xml.adapt();
        }
        DeviceApkImporter importer=new DeviceApkImporter(context,dir);
        File prepared=importer.prepare(source,analysis,text->{});
        assertTrue(prepared.isFile());
        PackageInfo info=context.getPackageManager().getPackageArchiveInfo(prepared.getPath(),0);
        assertNotNull(info);assertEquals(context.getPackageName(),info.packageName);
        // Read the compiled attribute through Android's XML parser. PackageInfo flags
        // are not a reliable proxy for extraction on archives with no native entries.
        info.applicationInfo.sourceDir=prepared.getAbsolutePath();
        info.applicationInfo.publicSourceDir=prepared.getAbsolutePath();
        android.content.res.Resources resources=context.getPackageManager().getResourcesForApplication(info.applicationInfo);
        boolean applicationFound=false;
        try(android.content.res.XmlResourceParser xml=resources.getAssets().openXmlResourceParser("AndroidManifest.xml")) {
            for(int event=xml.getEventType();event!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT;event=xml.next()) {
                if(event==org.xmlpull.v1.XmlPullParser.START_TAG&&"application".equals(xml.getName())) {
                    applicationFound=true;
                    assertTrue(xml.getAttributeBooleanValue("http://schemas.android.com/apk/res/android","extractNativeLibs",false));
                    break;
                }
            }
        }
        assertTrue(applicationFound);
        java.security.KeyStore store=java.security.KeyStore.getInstance("AndroidKeyStore");store.load(null);
        byte[] cert=store.getCertificate("nexa-apk-import-v1").getEncoded();
        importer.prepare(source,analysis,text->{});
        assertArrayEquals(cert,store.getCertificate("nexa-apk-import-v1").getEncoded());
        prepared.delete();
    }
}
