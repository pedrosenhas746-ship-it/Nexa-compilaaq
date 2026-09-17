package pojlib;

import android.app.Activity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import pojlib.util.Logger;
import pojlib.util.json.MinecraftInstances;

/** Installs the Nexa-patched Vivecraft that is bundled inside the Pojlib AAR. */
public final class NexaEmbeddedMods {
    private static final String ASSET = "nexa/vivecraft-nexa-1.20.4-fabric.jar";

    private NexaEmbeddedMods() {}

    public static void install(Activity activity, MinecraftInstances.Instance instance) {
        if (activity == null || instance == null || instance.gameDir == null) return;
        // Phase 1.2 provider is built against Minecraft 1.20.4 only.
        if (!"1.20.4".equals(instance.versionName)) return;

        File mods = new File(instance.gameDir, "mods");
        if (!mods.exists() && !mods.mkdirs()) {
            Logger.getInstance().appendToLog("Nexa: failed to create mods directory");
            return;
        }

        File target = new File(mods, "Vivecraft.jar");
        File temp = new File(mods, "Vivecraft.jar.nexa.tmp");
        try (InputStream in = activity.getAssets().open(ASSET);
             FileOutputStream out = new FileOutputStream(temp, false)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) out.write(buffer, 0, read);
            }
            out.getFD().sync();
        } catch (Throwable t) {
            temp.delete();
            Logger.getInstance().appendToLog("Nexa: bundled Vivecraft install failed: " + t);
            return;
        }

        if (target.exists() && !target.delete()) {
            temp.delete();
            Logger.getInstance().appendToLog("Nexa: failed to replace old Vivecraft.jar");
            return;
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            Logger.getInstance().appendToLog("Nexa: failed to finalize bundled Vivecraft.jar");
            return;
        }
        Logger.getInstance().appendToLog("Nexa: bundled Vivecraft provider installed");
    }
}
