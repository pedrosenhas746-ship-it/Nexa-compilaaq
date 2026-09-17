package com.nexa.questcraft;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

import pojlib.API;
import pojlib.InstanceHandler;
import pojlib.UnityPlayerActivity;
import pojlib.util.Constants;
import pojlib.util.json.MinecraftInstances;

/** Native Android launcher. No Unity runtime is used. */
public final class NexaQuestActivity extends UnityPlayerActivity {
    private static final String INSTANCE_NAME = "Nexa QuestCraft 1.20.4";
    private static final String MC_VERSION = "1.20.4";

    private final AtomicBoolean launching = new AtomicBoolean(false);
    private LinearLayout overlay;
    private TextView status;
    private Button login;
    private Button play;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        watchLoginState();
    }

    private void buildUi() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setPadding(48, 48, 48, 48);
        overlay.setBackgroundColor(Color.rgb(5, 10, 18));

        TextView title = text("NEXA QUESTCRAFT", 28f, Color.WHITE);
        overlay.addView(title);
        TextView subtitle = text("Android Studio Native • VRBox • 6DoF • Controle + maos", 14f,
                Color.rgb(145, 190, 230));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = 16;
        overlay.addView(subtitle, subLp);

        status = text("Entre com Microsoft. Se a conta nao possuir Java, o Pojlib usa o Demo Mode oficial.",
                15f, Color.LTGRAY);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = 30;
        overlay.addView(status, statusLp);

        login = new Button(this);
        login.setText("ENTRAR MICROSOFT / DEMO");
        login.setOnClickListener(v -> {
            status.setText("Iniciando login por codigo do dispositivo...");
            API.login(this, null);
        });
        LinearLayout.LayoutParams loginLp = new LinearLayout.LayoutParams(buttonWidth(), -2);
        loginLp.topMargin = 24;
        overlay.addView(login, loginLp);

        play = new Button(this);
        play.setText("JOGAR QUESTCRAFT");
        play.setEnabled(false);
        play.setOnClickListener(v -> beginLaunch());
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(buttonWidth(), -2);
        playLp.topMargin = 14;
        overlay.addView(play, playLp);

        getRootLayout().addView(overlay, new android.widget.FrameLayout.LayoutParams(-1, -1));
    }

    private TextView text(String value, float size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private int buttonWidth() {
        return Math.max(430, getResources().getDisplayMetrics().widthPixels / 3);
    }

    private void watchLoginState() {
        Thread watcher = new Thread(() -> {
            while (!isFinishing()) {
                try {
                    if (API.currentAcc != null) {
                        runOnUiThread(() -> {
                            login.setEnabled(false);
                            play.setEnabled(true);
                            status.setText(API.currentAcc.isDemoMode
                                    ? "Demo Mode oficial pronto: " + API.currentAcc.username
                                    : "Conta Minecraft pronta: " + API.currentAcc.username);
                        });
                        return;
                    }
                    String msg = API.msaMessage;
                    if (msg != null && !msg.isEmpty()) runOnUiThread(() -> status.setText(msg));
                    Thread.sleep(250L);
                } catch (Throwable ignored) { return; }
            }
        }, "NexaQuest-LoginWatcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void setStatus(String value) {
        runOnUiThread(() -> status.setText(value));
    }

    private void beginLaunch() {
        if (API.currentAcc == null || !launching.compareAndSet(false, true)) return;
        play.setEnabled(false);
        setStatus("Preparando Minecraft/Fabric 1.20.4...");

        new Thread(() -> {
            try {
                Constants.initConstants(this);
                MinecraftInstances all = API.loadAll();
                MinecraftInstances.Instance existing = findInstance(all);
                if (existing != null) {
                    prelaunchAndRun(all, existing);
                    return;
                }

                setStatus("Primeira execucao: baixando Minecraft/Fabric...");
                InstanceHandler.create(this, all, INSTANCE_NAME, Constants.USER_HOME, true,
                        MC_VERSION, "Fabric", null, created -> {
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
            if (i != null && (INSTANCE_NAME.equals(i.instanceName) || MC_VERSION.equals(i.versionName))) return i;
        }
        return null;
    }

    private void prelaunchAndRun(MinecraftInstances all, MinecraftInstances.Instance instance) {
        try {
            setStatus("Aplicando Vivecraft Nexa e preparando JVM...");
            API.prelaunch(this, all, instance);
            API.currentInstance = instance;
            runOnUiThread(() -> overlay.setVisibility(View.GONE));
            API.launchInstance(this, API.currentAcc, instance);
        } catch (Throwable t) {
            fail("Falha ao iniciar: " + shortMessage(t));
        }
    }

    private void fail(String value) {
        launching.set(false);
        runOnUiThread(() -> {
            overlay.setVisibility(View.VISIBLE);
            play.setEnabled(API.currentAcc != null);
            status.setText(value);
        });
    }

    private static String shortMessage(Throwable t) {
        String value = t.getMessage();
        if (value == null || value.trim().isEmpty()) value = t.getClass().getSimpleName();
        return value.length() > 180 ? value.substring(0, 180) : value;
    }
}
