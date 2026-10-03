package com.oculus.systemdriver;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.IOException;

/** Signatures from the user-supplied driver source. ABI compatibility is unverified. */
public final class DriverLoader {
    private static boolean loaded;
    private static native long procAddress();
    public static long load64(Context app, Context driver, int a,int b,int c,int d,int e) { return load(driver, true); }
    public static long load64Ext(Context app, Context driver, int a,int b,int c,int d,int e,long f) { return load(driver, true); }
    public static long load32(Context app, Context driver, int a,int b,int c,int d,int e) { return load(driver, false); }
    public static long load32Ext(Context app, Context driver, int a,int b,int c,int d,int e,long f) { return load(driver, false); }
    private static synchronized long load(Context driver, boolean requested64) {
        if (driver == null || requested64 != android.os.Process.is64Bit()) return 0;
        try {
            if (!loaded) {
                File folder = NativeLibraryDirectory.resolve(new File(driver.getApplicationInfo().nativeLibraryDir), requested64);
                System.load(new File(folder, "libopenxr_loader.so").getPath());
                System.load(new File(folder, "libvrapi.so").getPath());
                loaded = true;
            }
            return procAddress();
        } catch (IOException | LinkageError | RuntimeException e) {
            Log.e("HB-VrApiDriver", "Cannot load legacy adapter for this game process", e);
            return 0;
        }
    }
    private DriverLoader() {}
}
