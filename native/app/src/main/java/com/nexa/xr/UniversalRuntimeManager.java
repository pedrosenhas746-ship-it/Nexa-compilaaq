package com.nexa.xr;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ConfigurationInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;

import java.util.Arrays;

public final class UniversalRuntimeManager {
    public static final class DeviceCaps {
        public final boolean arm64;
        public final boolean gyro;
        public final boolean rotationVector;
        public final boolean vulkan;
        public final int glEsVersion;

        DeviceCaps(boolean arm64, boolean gyro, boolean rotationVector, boolean vulkan, int glEsVersion) {
            this.arm64 = arm64;
            this.gyro = gyro;
            this.rotationVector = rotationVector;
            this.vulkan = vulkan;
            this.glEsVersion = glEsVersion;
        }

        public String summary() {
            int major = (glEsVersion >> 16) & 0xffff;
            int minor = glEsVersion & 0xffff;
            return "ARM64 " + yesNo(arm64) +
                    " • Gyro " + yesNo(gyro) +
                    " • Vulkan " + yesNo(vulkan) +
                    " • GLES " + major + "." + minor;
        }

        private static String yesNo(boolean v) {
            return v ? "OK" : "NAO";
        }
    }

    private final Context context;
    private final PackageManager pm;

    public UniversalRuntimeManager(Context context) {
        this.context = context.getApplicationContext();
        this.pm = context.getPackageManager();
    }

    public DeviceCaps getDeviceCaps() {
        boolean arm64 = Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a");
        SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        boolean gyro = sm != null && sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
        boolean rotation = sm != null && sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null;
        boolean vulkan = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL);

        int gles = 0;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ConfigurationInfo info = am.getDeviceConfigurationInfo();
            if (info != null) gles = info.reqGlEsVersion;
        }
        return new DeviceCaps(arm64, gyro, rotation, vulkan, gles);
    }

    public String compatibilityDecision(QuestAppProfile p, DeviceCaps caps) {
        if (!p.launchable) return "SEM ACTIVITY DE START";
        if (p.arm64 && !caps.arm64) return "ABI ARM64 INCOMPATIVEL";
        if (p.openXrLoader) return "TENTAR OPENXR + FALLBACK";
        if (p.vrApi || p.ovrPlugin) return "REQUER QUEST SHIM";
        if ("UNITY".equals(p.engine) || "UNREAL".equals(p.engine)) return "TENTAR MODO ANDROID/FLAT";
        return "START ANDROID PADRAO";
    }

    public boolean launch(QuestAppProfile p) {
        Intent launch = pm.getLaunchIntentForPackage(p.packageName);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launch.putExtra("nexa_runtime", "universal-v2");
        try {
            context.startActivity(launch);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
