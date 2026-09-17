package com.nexa.questcraft;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

import pojlib.API;
import pojlib.InstanceHandler;
import pojlib.UnityPlayerActivity;
import pojlib.util.Constants;
import pojlib.util.json.MinecraftInstances;

/**
 * Native Android QuestCraft launcher.
 *
 * The original QuestCraft lodge is the launcher UI. No permanent Android
 * button panel is drawn over the room. A tiny status banner is only shown
 * while authentication / installation is active or when an error occurs.
 */
public final class NexaQuestActivity extends UnityPlayerActivity {
    private static final String INSTANCE_NAME = "Nexa QuestCraft 1.20.4";
    private static final String MC_VERSION = "1.20.4";

    private final AtomicBoolean launching = new AtomicBoolean(false);
    private final AtomicBoolean loginRunning = new AtomicBoolean(false);

    private NexaLodgeView lodgeView;
    private TextView statusBanner;
    private volatile String lastMsaMessage = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildLodgeUi();
        watchLoginState();
    }

    private void buildLodgeUi() {
        lodgeView = new NexaLodgeView(this);
        lodgeView.setMenuListener(this::onLodgeMenuAction);
        FrameLayout.LayoutParams lodgeLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);

        // Root also owns Minecraft's SurfaceView and the tiny ARCore tracker.
        // The lodge sits above the Minecraft surface while the launcher is open.
        getRootLayout().addView(
                lodgeView,
                Math.min(1, getRootLayout().getChildCount()),
                lodgeLp);

        // Useful when testing without the phone inside the VRBox.
        lodgeView.setOnClickListener(v -> activatePrimaryAction());

        statusBanner = new TextView(this);
        statusBanner.setTextColor(Color.WHITE);
        statusBanner.setTextSize(14f);
        statusBanner.setGravity(Gravity.CENTER);
        statusBanner.setPadding(24, 14, 24, 14);
        statusBanner.setBackgroundColor(Color.argb(205, 4, 8, 14));
        statusBanner.setVisibility(View.GONE);

        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        getRootLayout().addView(statusBanner, statusLp);
    }

    /**
     * In the lodge, A / Enter is the primary Quest-style select action.
     * Once Minecraft owns the screen all key events go through Pojlib normally.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (lodgeView != null
                && lodgeView.getVisibility() == View.VISIBLE
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_BUTTON_A
                    || key == KeyEvent.KEYCODE_ENTER
                    || key == KeyEvent.KEYCODE_DPAD_CENTER) {
                activatePrimaryAction();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void onLodgeMenuAction(String buttonName) {
        if (buttonName == null) return;
        String n = buttonName.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("play")) {
            activatePrimaryAction();
            return;
        }
        if (n.contains("account") || n.contains("login") || n.contains("microsoft")) {
            beginLogin();
            return;
        }
        if (n.contains("instance")) {
            showStatus("Instances reconhecido no CRT original • selecao nativa em integracao");
            hideStatusLater(1800L);
            return;
        }
        if (n.contains("mod")) {
            showStatus("Mods reconhecido no CRT original • navegador nativo em integracao");
            hideStatusLater(1800L);
        }
    }

    private void activatePrimaryAction() {
        if (launching.get()) return;
        if (API.currentAcc == null) {
            beginLogin();
        } else {
            beginLaunch();
        }
    }

    private void beginLogin() {
        if (!loginRunning.compareAndSet(false, true)) return;
        showStatus("Conectando conta Microsoft / Demo Mode...");
        try {
            API.login(this, null);
        } catch (Throwable t) {
            loginRunning.set(false);
            showStatus("Falha no login: " + shortMessage(t));
        }
    }

    private void watchLoginState() {
        Thread watcher = new Thread(() -> {
            while (!isFinishing()) {
                try {
                    if (API.currentAcc != null) {
                        loginRunning.set(false);
                        String name = API.currentAcc.username == null
                                ? "Conta pronta" : API.currentAcc.username;
                        showStatus(API.currentAcc.isDemoMode
                                ? "Demo Mode oficial pronto: " + name + " • pressione A para jogar"
                                : "Conta Minecraft pronta: " + name + " • pressione A para jogar");
                        hideStatusLater(2600L);
                        return;
                    }

                    String msg = API.msaMessage;
                    if (msg != null) {
                        msg = msg.trim();
                        if (!msg.isEmpty() && !msg.equals(lastMsaMessage)) {
                            lastMsaMessage = msg;
                            showStatus(msg);
                        }
                    }
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) {
                    return;
                }
            }
        }, "NexaQuest-LoginWatcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void showStatus(String value) {
        runOnUiThread(() -> {
            if (statusBanner == null) return;
            statusBanner.setText(value == null ? "" : value);
            statusBanner.setVisibility(View.VISIBLE);
        });
    }

    private void hideStatus() {
        runOnUiThread(() -> {
            if (statusBanner != null) statusBanner.setVisibility(View.GONE);
        });
    }

    private void hideStatusLater(long delayMs) {
        runOnUiThread(() -> {
            if (statusBanner == null) return;
            statusBanner.removeCallbacks(hideStatusRunnable);
            statusBanner.postDelayed(hideStatusRunnable, delayMs);
        });
    }

    private final Runnable hideStatusRunnable = () -> {
        if (statusBanner != null && !launching.get()) {
            statusBanner.setVisibility(View.GONE);
        }
    };

    private void beginLaunch() {
        if (API.currentAcc == null || !launching.compareAndSet(false, true)) return;
        showStatus("Preparando Minecraft / Fabric 1.20.4...");

        new Thread(() -> {
            try {
                Constants.initConstants(this);
                MinecraftInstances all = API.loadAll();
                MinecraftInstances.Instance existing = findInstance(all);
                if (existing != null) {
                    prelaunchAndRun(all, existing);
                    return;
                }

                showStatus("Primeira execucao: baixando Minecraft / Fabric...");
                InstanceHandler.create(
                        this,
                        all,
                        INSTANCE_NAME,
                        Constants.USER_HOME,
                        true,
                        MC_VERSION,
                        "Fabric",
                        null,
                        created -> {
                            if (created == null) {
                                fail("Falha ao criar instancia");
                                return;
                            }
                            prelaunchAndRun(all, created);
                        });
            } catch (Throwable t) {
                fail("Erro: " + shortMessage(t));
            }
        }, "NexaQuest-Launcher").start();
    }

    private MinecraftInstances.Instance findInstance(MinecraftInstances all) {
        if (all == null || all.instances == null) return null;
        for (MinecraftInstances.Instance i : all.instances) {
            if (i != null
                    && (INSTANCE_NAME.equals(i.instanceName)
                    || MC_VERSION.equals(i.versionName))) {
                return i;
            }
        }
        return null;
    }

    private void prelaunchAndRun(
            MinecraftInstances all,
            MinecraftInstances.Instance instance) {
        try {
            showStatus("Aplicando Vivecraft Nexa e preparando JVM...");
            API.prelaunch(this, all, instance);
            API.currentInstance = instance;

            runOnUiThread(() -> {
                hideStatus();
                if (lodgeView != null) {
                    lodgeView.pauseRendering();
                    lodgeView.setVisibility(View.GONE);
                }
            });

            API.launchInstance(this, API.currentAcc, instance);
        } catch (Throwable t) {
            fail("Falha ao iniciar: " + shortMessage(t));
        }
    }

    private void fail(String value) {
        launching.set(false);
        runOnUiThread(() -> {
            if (lodgeView != null) {
                lodgeView.setVisibility(View.VISIBLE);
                lodgeView.resumeRendering();
            }
            showStatus(value);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lodgeView != null && lodgeView.getVisibility() == View.VISIBLE) {
            lodgeView.resumeRendering();
        }
    }

    @Override
    protected void onPause() {
        if (lodgeView != null) lodgeView.pauseRendering();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (statusBanner != null) statusBanner.removeCallbacks(hideStatusRunnable);
        if (lodgeView != null) lodgeView.destroyRenderer();
        super.onDestroy();
    }

    private static String shortMessage(Throwable t) {
        String value = t.getMessage();
        if (value == null || value.trim().isEmpty()) {
            value = t.getClass().getSimpleName();
        }
        return value.length() > 180 ? value.substring(0, 180) : value;
    }
}
