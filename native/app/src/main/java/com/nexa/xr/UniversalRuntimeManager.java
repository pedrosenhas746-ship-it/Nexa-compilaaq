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
        boolean rotation = sm != null && (sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null || sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null);
        boolean vulkan = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL);

        int gles = 0;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ConfigurationInfo info = am.getDeviceConfigurationInfo();
            if (info != null) gles = info.reqGlEsVersion;
        }
        return new DeviceCaps(arm64, gyro, rotation, vulkan, gles);
    }

    public static boolean compatibleAbi(QuestAppProfile p) {
        if(!p.arm64&&!p.arm32&&!p.x86_64)return true;
        for(String abi:Build.SUPPORTED_ABIS)if((p.arm64&&abi.equals("arm64-v8a"))||(p.arm32&&abi.equals("armeabi-v7a"))||(p.x86_64&&abi.equals("x86_64")))return true;
        return false;
    }
    public String runtimeSummary() {
        StringBuilder s=new StringBuilder();
        for(String pkg:new String[]{PHONE_XR.getPackageName(),VRAPI_DRIVER.getPackageName()}) {
            try {android.content.pm.PackageInfo info=pm.getPackageInfo(pkg,0);s.append(pkg.equals(PHONE_XR.getPackageName())?"PhoneXR: ":"Driver VrApi: ").append(info.versionName).append(" / código ").append((Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode)).append("\n");}
            catch(PackageManager.NameNotFoundException e){s.append(pkg.equals(PHONE_XR.getPackageName())?"PhoneXR: ausente\n":"Driver VrApi: ausente\n");}
        }
        return s.toString();
    }
    public boolean launchRuntimeProbe() {
        error="";
        ComponentName probe=new ComponentName(context,RuntimeProbeActivity.class);
        Intent intent=available(PHONE_XR)?new Intent().setComponent(PHONE_XR).putExtra("component",probe.flattenToString()).putExtra("vrapi",false):new Intent().setComponent(probe);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try{context.startActivity(intent);return true;}catch(Exception e){error="Falha ao verificar runtime: "+e;return false;}
    }

    public String compatibilityDecision(QuestAppProfile p, DeviceCaps caps) {
        if (!p.launchable) return "SEM ACTIVITY DE START";
        if (!compatibleAbi(p)) return "ABI incompatível: celular "+Arrays.toString(Build.SUPPORTED_ABIS);
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
        if (!p.launchable || !compatibleAbi(p)) {
            error = compatibilityDecision(p, caps);
            return false;
        }
        ComponentName component = QuestLaunchResolver.resolve(pm, p.packageName);
        if (component == null) {
            error = "Jogo sem atividade de abertura";
            return false;
        }
        Intent launch = new Intent(Intent.ACTION_MAIN).setComponent(component);
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
        launch.putExtra("nexa_runtime", "quest-bridge-v4.2");
        try {
            context.startActivity(launch);
            return true;
        } catch (Exception e) {
            error = "Falha ao abrir: " + e.getClass().getSimpleName();
            return false;
        }
    }
}
