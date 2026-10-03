package com.nexa.xr;

import android.app.ActivityManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
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
    private String error = "";
    private static final ComponentName PHONE_XR = new ComponentName(
            "org.freedesktop.monado.openxr_runtime.out_of_process",
            "org.freedesktop.monado.phonexr.GameLauncher");
    private static final ComponentName VRAPI_DRIVER = new ComponentName(
            "com.oculus.systemdriver", "dev.phonexr.vrapidriver.GameLauncher");

    public String lastError() { return error; }

    private boolean available(ComponentName name) {
        try {
            ActivityInfo info = pm.getActivityInfo(name, 0);
            return info.exported && info.enabled && info.applicationInfo.enabled;
        } catch (PackageManager.NameNotFoundException e) { return false; }
    }

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
        if (p.vrApi) return available(PHONE_XR) && available(VRAPI_DRIVER)
                ? "VRAPI EXPERIMENTAL VIA PHONEXR" : "INSTALE PHONEXR + DRIVER VRAPI";
        if (p.openXrLoader) return available(PHONE_XR)
                ? "OPENXR VIA PHONEXR" : "INSTALE PHONEXR";
        if (p.ovrPlugin) return "OVRPLUGIN: RUNTIME NATIVO NAO IDENTIFICADO";
        if ("UNITY".equals(p.engine) || "UNREAL".equals(p.engine)) return "TENTAR MODO ANDROID/FLAT";
        return "START ANDROID PADRAO";
    }

    public boolean launch(QuestAppProfile p) {
        error = "";
        DeviceCaps caps = getDeviceCaps();
        if (!p.launchable || (p.arm64 && !p.arm32 && !caps.arm64)) {
            error = compatibilityDecision(p, caps);
            return false;
        }
        Intent launch = pm.getLaunchIntentForPackage(p.packageName);
        if (launch == null || launch.getComponent() == null) {
            error = "Jogo sem atividade de abertura";
            return false;
        }
        if (p.vrApi || p.openXrLoader) {
            if (!available(PHONE_XR) || (p.vrApi && !available(VRAPI_DRIVER))) {
                error = compatibilityDecision(p, caps);
                return false;
            }
            // The runtime launcher grants its visibility URI to the game process.
            // VrApi additionally routes through the external driver; no silent
            // flat launch is presented as XR emulation.
            launch = new Intent().setComponent(PHONE_XR)
                    .putExtra("component", launch.getComponent().flattenToString())
                    .putExtra("vrapi", p.vrApi);
        } else if (p.ovrPlugin) {
            error = "OVRPlugin sem VrApi/OpenXR detectavel. Analise o APK e splits.";
            return false;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launch.putExtra("nexa_runtime", "quest-bridge-v3");
        try {
            context.startActivity(launch);
            return true;
        } catch (Exception e) {
            error = "Falha ao abrir: " + e.getClass().getSimpleName();
            return false;
        }
    }
}
