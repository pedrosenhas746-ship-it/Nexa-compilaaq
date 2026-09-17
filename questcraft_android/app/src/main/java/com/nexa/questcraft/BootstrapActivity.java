package com.nexa.questcraft;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Crash-isolated launcher. This class intentionally has zero Pojlib/Filament/ARCore
 * references so the app can always reach a visible screen before the heavy runtime.
 */
public final class BootstrapActivity extends Activity {
    private static final String PREFS = "nexa_boot";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button retry;
    private boolean runtimeStarted;
    private long launchMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        hideSystemUi();
        buildUi();

        writeStage("bootstrap_ready", "");

        status.setText("NEXA QUESTCRAFT\nInicializando runtime VR...");
        handler.postDelayed(this::launchRuntime, 450L);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 40, 48, 40);
        root.setBackgroundColor(Color.rgb(4, 8, 14));

        TextView title = new TextView(this);
        title.setText("NEXA QUESTCRAFT");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28f);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        status = new TextView(this);
        status.setTextColor(Color.rgb(185, 215, 240));
        status.setTextSize(16f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 28, 0, 28);

        retry = new Button(this);
        retry.setText("TENTAR NOVAMENTE");
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> {
            retry.setVisibility(View.GONE);
            status.setText("Reiniciando runtime VR...");
            launchRuntime();
        });

        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(retry, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private void launchRuntime() {
        if (runtimeStarted) return;
        runtimeStarted = true;
        launchMs = System.currentTimeMillis();

        writeStage("bootstrap_launching_runtime", "");

        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    getPackageName(),
                    "com.nexa.questcraft.NexaQuestActivity"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
            overridePendingTransition(0, 0);
        } catch (Throwable t) {
            runtimeStarted = false;
            showFailure("Nao foi possivel iniciar o runtime: " + shortMessage(t));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();

        if (!runtimeStarted) return;
        handler.postDelayed(() -> {
            if (!hasWindowFocus() && !isFinishing()) return;

            String[] boot = readStage();
            String stage = boot[0];
            String detail = boot[1];

            // If runtime started successfully, Bootstrap normally stays paused.
            // Returning here shortly after launch means the isolated process died
            // or the runtime Activity closed itself.
            if (System.currentTimeMillis() - launchMs > 700L) {
                runtimeStarted = false;
                if ("lodge_ready".equals(stage) || "runtime_ready".equals(stage)) {
                    showFailure("O runtime fechou depois de abrir. Estagio: " + stage
                            + (detail.isEmpty() ? "" : "\n" + detail));
                } else {
                    showFailure("O runtime VR nao conseguiu iniciar. Estagio: " + stage
                            + (detail.isEmpty() ? "" : "\n" + detail));
                }
            }
        }, 900L);
    }

    private void writeStage(String stage, String detail) {
        File file = new File(getFilesDir(), STAGE_FILE);
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            String value = stage + "\n" + (detail == null ? "" : detail) + "\n"
                    + System.currentTimeMillis() + "\n";
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable ignored) { }
    }

    private String[] readStage() {
        File file = new File(getFilesDir(), STAGE_FILE);
        if (!file.isFile()) return new String[]{"desconhecido", ""};
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String stage = reader.readLine();
            String detail = reader.readLine();
            return new String[]{
                    stage == null || stage.isEmpty() ? "desconhecido" : stage,
                    detail == null ? "" : detail
            };
        } catch (Throwable ignored) {
            return new String[]{"desconhecido", ""};
        }
    }

    private void showFailure(String message) {
        status.setText(message);
        retry.setVisibility(View.VISIBLE);
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private static String shortMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) m = t.getClass().getSimpleName();
        return m.length() > 220 ? m.substring(0, 220) : m;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
