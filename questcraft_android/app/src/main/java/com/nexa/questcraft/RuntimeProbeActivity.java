package com.nexa.questcraft;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal crash-isolated runtime probe.
 *
 * IMPORTANT: this class intentionally has no compile-time references to Pojlib,
 * ARCore, MediaPipe, Filament or LWJGL. It can therefore start and record the
 * exact boot failure even when the heavy runtime cannot be linked/verified.
 */
public final class RuntimeProbeActivity extends Activity {
    private static final String STAGE_FILE = "nexa_boot_stage.txt";
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(Color.rgb(4, 8, 14));
        status.setTextSize(16f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(40, 40, 40, 40);
        status.setText("NEXA QUESTCRAFT\nVerificando runtime...");
        setContentView(status);

        mark("runtime_probe_entered", processInfo());

        Thread t = new Thread(this::runProbe, "NexaQuest-RuntimeProbe");
        t.setDaemon(true);
        t.start();
    }

    private void runProbe() {
        try {
            ClassLoader loader = getClassLoader();

            probeClass(loader, "org.lwjgl.glfw.CallbackBridge");
            probeClass(loader, "fr.spse.gamepad_remapper.RemapperManager");
            probeClass(loader, "pojlib.NexaTrackingSurface");
            probeClass(loader, "pojlib.UnityPlayerActivity");

            mark("runtime_probe_classes_ok", nativeDir());

            try {
                System.loadLibrary("pojavexec");
                mark("runtime_probe_native_ok", nativeDir());
            } catch (Throwable e) {
                fail("runtime_probe_native_error", e);
                return;
            }

            try {
                Class.forName("com.nexa.questcraft.NexaQuestActivity", true, loader);
                mark("runtime_probe_activity_class_ok", "");
            } catch (Throwable e) {
                fail("runtime_probe_activity_class_error", e);
                return;
            }

            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent();
                    intent.setComponent(new ComponentName(
                            getPackageName(),
                            "com.nexa.questcraft.NexaQuestActivity"));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    mark("runtime_probe_launching_activity", "");
                    startActivity(intent);
                    overridePendingTransition(0, 0);
                    finish();
                } catch (Throwable e) {
                    fail("runtime_probe_start_error", e);
                }
            });
        } catch (Throwable e) {
            fail("runtime_probe_unexpected_error", e);
        }
    }

    private void probeClass(ClassLoader loader, String name) throws Exception {
        try {
            Class.forName(name, false, loader);
        } catch (Throwable e) {
            throw new Exception("Class " + name + ": " + describe(e), e);
        }
    }

    private void fail(String stage, Throwable e) {
        String detail = describeChain(e);
        mark(stage, detail);
        runOnUiThread(() -> status.setText(
                "NEXA QUESTCRAFT\n\nFalha no runtime:\n" + stage
                        + "\n\n" + detail
                        + "\n\nVolte para tentar novamente."));
    }

    private String nativeDir() {
        try {
            return "nativeLibraryDir=" + getApplicationInfo().nativeLibraryDir;
        } catch (Throwable e) {
            return "nativeLibraryDir=?";
        }
    }

    private String processInfo() {
        return "pid=" + android.os.Process.myPid()
                + "; sdk=" + android.os.Build.VERSION.SDK_INT
                + "; abi=" + android.os.Build.SUPPORTED_ABIS[0]
                + "; " + nativeDir();
    }

    private void mark(String stage, String detail) {
        File file = new File(getFilesDir(), STAGE_FILE);
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            String value = stage + "\n"
                    + sanitize(detail) + "\n"
                    + System.currentTimeMillis() + "\n";
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable ignored) { }
    }

    private static String describeChain(Throwable t) {
        StringBuilder b = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 5) {
            if (b.length() > 0) b.append(" <- ");
            b.append(describe(cur));
            Throwable next = cur.getCause();
            if (next == cur) break;
            cur = next;
            depth++;
        }
        String s = b.toString();
        return s.length() > 700 ? s.substring(0, 700) : s;
    }

    private static String describe(Throwable t) {
        if (t == null) return "erro desconhecido";
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) m = "(sem mensagem)";
        return t.getClass().getSimpleName() + ": " + m;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ');
    }
}
