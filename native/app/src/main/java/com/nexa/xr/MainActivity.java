package com.nexa.xr;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity implements SensorEventListener {
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private NexaView nexaView;
    private QuestCompatibilityScanner scanner;
    private UniversalRuntimeManager runtime;
    private UniversalRuntimeManager.DeviceCaps deviceCaps;
    private final List<QuestAppProfile> apps = new ArrayList<>();
    private int selectedIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        scanner = new QuestCompatibilityScanner(this);
        runtime = new UniversalRuntimeManager(this);
        deviceCaps = runtime.getDeviceCaps();

        nexaView = new NexaView(this);
        setContentView(nexaView);
        try {
            startService(new android.content.Intent(this, NexaRuntimeBridgeService.class));
        } catch (Exception ignored) {
        }
        nexaView.setMessage("Quest Bridge v3 pronto • bridge v" + NexaRuntimeContract.PROTOCOL_VERSION);
        scanApps();
    }

    private void hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        float[] rotation = new float[9];
        float[] orientation = new float[3];
        SensorManager.getRotationMatrixFromVector(rotation, event.values);
        SensorManager.getOrientation(rotation, orientation);
        nexaView.setHeadPose(
                (float) Math.toDegrees(orientation[0]),
                (float) Math.toDegrees(orientation[1]),
                (float) Math.toDegrees(orientation[2]));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private synchronized QuestAppProfile selectedApp() {
        if (selectedIndex < 0 || selectedIndex >= apps.size()) return null;
        return apps.get(selectedIndex);
    }

    private void scanApps() {
        nexaView.setMessage("Escaneando apps e runtimes...");
        new Thread(() -> {
            List<QuestAppProfile> scanned = scanner.scanInstalledApps();
            synchronized (MainActivity.this) {
                apps.clear();
                apps.addAll(scanned);
                selectedIndex = apps.isEmpty() ? -1 : 0;
            }
            nexaView.post(() -> {
                QuestAppProfile p = selectedApp();
                if (p == null) {
                    nexaView.setMessage("Nenhum app analisavel encontrado");
                } else {
                    nexaView.setMessage("Scan: " + apps.size() + " apps • " + p.shortSummary());
                }
                nexaView.invalidate();
            });
        }, "nexa-app-scan").start();
    }

    private void nextApp() {
        synchronized (this) {
            if (apps.isEmpty()) {
                nexaView.setMessage("Lista vazia. Rode o scan.");
                return;
            }
            selectedIndex = (selectedIndex + 1) % apps.size();
        }
        QuestAppProfile p = selectedApp();
        nexaView.setMessage(p == null ? "Sem selecao" : p.shortSummary());
    }

    private void launchSelected() {
        QuestAppProfile p = selectedApp();
        if (p == null) {
            nexaView.setMessage("Nenhum app selecionado");
            return;
        }
        String decision = runtime.compatibilityDecision(p, deviceCaps);
        try {
            startService(new android.content.Intent(this, NexaRuntimeBridgeService.class));
        } catch (Exception ignored) {
        }
        if (runtime.launch(p)) {
            nexaView.setMessage("Abrindo " + p.label + " • " + decision);
        } else {
            nexaView.setMessage(runtime.lastError());
        }
    }

    private final class NexaView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float yaw;
        private float pitch;
        private float roll;
        private String message = "NEXA pronto";

        NexaView(Context context) {
            super(context);
            setBackgroundColor(Color.rgb(4, 8, 14));
            setFocusable(true);
        }

        void setHeadPose(float yaw, float pitch, float roll) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            postInvalidateOnAnimation();
        }

        void setMessage(String message) {
            this.message = message;
            postInvalidateOnAnimation();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            int half = w / 2;
            drawEye(canvas, 0, half, h);
            drawEye(canvas, half, half, h);

            paint.setColor(Color.rgb(42, 50, 64));
            paint.setStrokeWidth(2f);
            canvas.drawLine(half, 0, half, h, paint);
        }

        private void drawEye(Canvas c, int offsetX, int eyeW, int h) {
            float cx = offsetX + eyeW * 0.5f;
            float parallaxX = Math.max(-28f, Math.min(28f, yaw * 0.45f));
            float parallaxY = Math.max(-18f, Math.min(18f, pitch * 0.35f));
            QuestAppProfile p = selectedApp();

            paint.setColor(Color.rgb(11, 20, 34));
            c.drawRect(offsetX, 0, offsetX + eyeW, h, paint);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(Color.WHITE);
            paint.setTextSize(Math.max(22f, eyeW * 0.041f));
            paint.setFakeBoldText(true);
            c.drawText("NEXA QUEST BRIDGE v3", cx - parallaxX, h * 0.10f - parallaxY, paint);

            paint.setFakeBoldText(false);
            paint.setTextSize(Math.max(11f, eyeW * 0.017f));
            paint.setColor(Color.rgb(155, 190, 225));
            c.drawText(deviceCaps.summary(), cx - parallaxX, h * 0.145f - parallaxY, paint);
            c.drawText(String.format("HEAD yaw %.1f  pitch %.1f  roll %.1f", yaw, pitch, roll),
                    cx - parallaxX, h * 0.18f - parallaxY, paint);

            if (p != null) {
                paint.setFakeBoldText(true);
                paint.setTextSize(Math.max(15f, eyeW * 0.024f));
                paint.setColor(Color.WHITE);
                c.drawText(trim(p.label, 34), cx, h * 0.225f, paint);
                paint.setFakeBoldText(false);
                paint.setTextSize(Math.max(10f, eyeW * 0.016f));
                paint.setColor(Color.rgb(130, 190, 230));
                c.drawText(trim(p.shortSummary(), 52), cx, h * 0.255f, paint);
                c.drawText(trim(runtime.compatibilityDecision(p, deviceCaps), 52), cx, h * 0.278f, paint);
            } else {
                paint.setTextSize(Math.max(12f, eyeW * 0.018f));
                paint.setColor(Color.rgb(130, 190, 230));
                c.drawText("Nenhum app selecionado", cx, h * 0.245f, paint);
            }

            drawCard(c, offsetX, eyeW, h, 0, "SCAN APPS", "DETECTAR QUEST / OPENXR / OVR");
            drawCard(c, offsetX, eyeW, h, 1, "PROXIMO APP", apps.isEmpty() ? "LISTA VAZIA" : ((selectedIndex + 1) + "/" + apps.size()));
            drawCard(c, offsetX, eyeW, h, 2, "EXECUTAR", p == null ? "SEM APP" : p.mode);
            drawCard(c, offsetX, eyeW, h, 3, "RECENTRALIZAR", "HEAD POSE");

            paint.setTextSize(Math.max(11f, eyeW * 0.017f));
            paint.setColor(Color.rgb(210, 220, 235));
            c.drawText(trim(message, 62), cx, h * 0.94f, paint);

            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(2.5f);
            c.drawLine(cx - 10, h * 0.5f, cx + 10, h * 0.5f, paint);
            c.drawLine(cx, h * 0.5f - 10, cx, h * 0.5f + 10, paint);
        }

        private String trim(String s, int max) {
            if (s == null) return "";
            if (s.length() <= max) return s;
            return s.substring(0, Math.max(0, max - 1)) + "…";
        }

        private void drawCard(Canvas c, int offsetX, int eyeW, int h, int index, String title, String subtitle) {
            float left = offsetX + eyeW * 0.15f;
            float right = offsetX + eyeW * 0.85f;
            float top = h * (0.32f + index * 0.135f);
            float bottom = top + h * 0.095f;

            paint.setColor(Color.rgb(20, 38, 58));
            c.drawRoundRect(new RectF(left, top, right, bottom), 20f, 20f, paint);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            paint.setTextSize(Math.max(15f, eyeW * 0.023f));
            paint.setColor(Color.WHITE);
            c.drawText(title, (left + right) * 0.5f, top + (bottom - top) * 0.45f, paint);

            paint.setFakeBoldText(false);
            paint.setTextSize(Math.max(9f, eyeW * 0.014f));
            paint.setColor(Color.rgb(130, 190, 230));
            c.drawText(trim(subtitle, 36), (left + right) * 0.5f, top + (bottom - top) * 0.72f, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            int h = getHeight();
            float y = event.getY();

            if (y >= h * 0.30f && y < h * 0.445f) {
                scanApps();
            } else if (y >= h * 0.445f && y < h * 0.58f) {
                nextApp();
            } else if (y >= h * 0.58f && y < h * 0.715f) {
                launchSelected();
            } else if (y >= h * 0.715f && y < h * 0.85f) {
                yaw = 0f;
                pitch = 0f;
                roll = 0f;
                setMessage("Head pose recentralizada");
            }
            return true;
        }
    }
}

