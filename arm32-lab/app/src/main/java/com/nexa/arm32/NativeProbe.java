// SPDX-License-Identifier: GPL-2.0-only
package com.nexa.arm32;
public final class NativeProbe {
    static { System.loadLibrary("nexa_arm32"); }
    private NativeProbe(){}
    public static native String selfTest();
    public static native String inspect(String path,boolean jni);
}
