package com.nexa.xr;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
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

        ArchiveFlags flags = new ArchiveFlags();
        inspectArchive(app.sourceDir, flags);
        if (app.splitSourceDirs != null) {
            for (String split : app.splitSourceDirs) inspectArchive(split, flags);
        }

        String engine = flags.unity ? "UNITY" : flags.unreal ? "UNREAL" : "ANDROID";
        String mode;
        String notes;

        if (!launchable) {
            mode = "NO_LAUNCH";
            notes = "Pacote sem Activity de inicializacao visivel.";
        } else if (flags.openxr) {
            mode = "OPENXR";
            notes = "Candidato OpenXR; runtime real ainda depende do dispositivo e do loader.";
        } else if (flags.vrApi || flags.ovrPlugin) {
            mode = "QUEST_SHIM";
            notes = "Usa APIs Quest/Oculus; NEXA prepara bridge/shim, mas app sem adaptacao ainda pode exigir servicos Meta.";
        } else if (flags.unity || flags.unreal) {
            mode = "ENGINE_FALLBACK";
            notes = "Engine 3D detectada; tenta caminho Android/flat quando o app oferece fallback.";
        } else {
            mode = "ANDROID";
            notes = "Runtime VR nao detectado; usa inicializacao Android padrao.";
        }

        return new QuestAppProfile(
                app.packageName, label, engine,
                flags.arm64, flags.arm32, flags.x86_64,
                flags.openxr, flags.ovrPlugin, flags.vrApi, flags.ovrPlatform,
                launchable, mode, notes);
    }

    private void inspectArchive(String path, ArchiveFlags f) {
        if (path == null || path.isEmpty()) return;
        try (ZipFile zip = new ZipFile(new File(path))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String n = entries.nextElement().getName().toLowerCase(Locale.ROOT);

                if (n.startsWith("lib/arm64-v8a/")) f.arm64 = true;
                else if (n.startsWith("lib/armeabi-v7a/")) f.arm32 = true;
                else if (n.startsWith("lib/x86_64/")) f.x86_64 = true;

                if (n.endsWith("/libunity.so") || n.contains("assets/bin/data/")) f.unity = true;
                if (n.endsWith("/libue4.so") || n.endsWith("/libunreal.so")) f.unreal = true;

                if (n.endsWith("/libopenxr_loader.so") || n.contains("openxr_loader")) f.openxr = true;
                if (n.endsWith("/libovrplugin.so") || n.contains("ovrplugin")) f.ovrPlugin = true;
                if (n.endsWith("/libvrapi.so") || n.contains("/vrapi")) f.vrApi = true;
                if (n.contains("ovrplatform") || n.contains("platformloader")) f.ovrPlatform = true;

                if (n.contains("oculusspatializer") || n.contains("audio_plugin_oculus")) {
                    f.oculusAudio = true;
                }
                if (n.contains("ovravatar") || n.contains("avatar2")) {
                    f.ovrAvatar = true;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static final class ArchiveFlags {
        boolean arm64;
        boolean arm32;
        boolean x86_64;
        boolean unity;
        boolean unreal;
        boolean openxr;
        boolean ovrPlugin;
        boolean vrApi;
        boolean ovrPlatform;
        boolean oculusAudio;
        boolean ovrAvatar;
    }
}
