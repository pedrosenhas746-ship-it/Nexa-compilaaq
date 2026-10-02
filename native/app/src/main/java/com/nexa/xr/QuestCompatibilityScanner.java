package com.nexa.xr;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class QuestCompatibilityScanner {
    private final Context context;
    private final PackageManager pm;

    public QuestCompatibilityScanner(Context context) {
        this.context = context.getApplicationContext();
        this.pm = context.getPackageManager();
    }

    public List<QuestAppProfile> scanInstalledApps() {
        List<QuestAppProfile> result = new ArrayList<>();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo app : apps) {
            if (context.getPackageName().equals(app.packageName)) continue;
            boolean launchable = pm.getLaunchIntentForPackage(app.packageName) != null;
            boolean system = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (system && !launchable) continue;

            QuestAppProfile profile = inspect(app, launchable);
            if (profile.isVrCandidate() || launchable) {
                result.add(profile);
            }
        }
        Collections.sort(result, (a, b) -> {
            if (a.isVrCandidate() != b.isVrCandidate()) return a.isVrCandidate() ? -1 : 1;
            String al = a.label == null ? a.packageName : a.label;
            String bl = b.label == null ? b.packageName : b.label;
            return String.CASE_INSENSITIVE_ORDER.compare(al, bl);
        });
        return result;
    }

    private QuestAppProfile inspect(ApplicationInfo app, boolean launchable) {
        String label;
        try {
            label = String.valueOf(pm.getApplicationLabel(app));
        } catch (Exception e) {
            label = app.packageName;
        }

        boolean arm64 = false;
        boolean arm32 = false;
        boolean x86_64 = false;
        boolean unity = false;
        boolean unreal = false;
        boolean openxr = false;
        boolean ovrPlugin = false;
        boolean vrApi = false;
        boolean ovrPlatform = false;

        String source = app.sourceDir;
        if (source != null) {
            try (ZipFile zip = new ZipFile(new File(source))) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String n = entries.nextElement().getName().toLowerCase(Locale.ROOT);
                    if (n.startsWith("lib/arm64-v8a/")) arm64 = true;
                    else if (n.startsWith("lib/armeabi-v7a/")) arm32 = true;
                    else if (n.startsWith("lib/x86_64/")) x86_64 = true;

                    if (n.endsWith("/libunity.so") || n.contains("/assets/bin/data/")) unity = true;
                    if (n.endsWith("/libue4.so") || n.endsWith("/libunreal.so")) unreal = true;
                    if (n.endsWith("/libopenxr_loader.so") || n.contains("openxr_loader")) openxr = true;
                    if (n.endsWith("/libovrplugin.so") || n.contains("ovrplugin")) ovrPlugin = true;
                    if (n.endsWith("/libvrapi.so") || n.contains("vrapi")) vrApi = true;
                    if (n.contains("ovrplatform") || n.contains("platformloader")) ovrPlatform = true;
                }
            } catch (Exception ignored) {
            }
        }

        String engine = unity ? "UNITY" : unreal ? "UNREAL" : "ANDROID";
        String mode;
        String notes;
        if (!launchable) {
            mode = "NO_LAUNCH";
            notes = "Pacote sem Activity de inicializacao visivel.";
        } else if (openxr) {
            mode = "OPENXR";
            notes = "Candidato OpenXR. Precisa de runtime OpenXR compativel no dispositivo.";
        } else if (vrApi || ovrPlugin) {
            mode = "QUEST_SHIM";
            notes = "Depende de APIs Quest/Oculus; requer camada de compatibilidade ou servicos equivalentes.";
        } else if (unity || unreal) {
            mode = "ENGINE_FALLBACK";
            notes = "Engine 3D detectada; pode abrir em modo Android/flat se o app oferecer fallback.";
        } else {
            mode = "ANDROID";
            notes = "App Android comum ou runtime VR nao detectado no APK.";
        }

        return new QuestAppProfile(
                app.packageName, label, engine,
                arm64, arm32, x86_64,
                openxr, ovrPlugin, vrApi, ovrPlatform,
                launchable, mode, notes);
    }
}
