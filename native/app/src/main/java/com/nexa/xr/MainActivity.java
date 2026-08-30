package com.nexa.xr;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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

public final class MainActivity extends Activity implements SensorEventListener {
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private NexaView nexaView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        nexaView = new NexaView(this);
        setContentView(nexaView);
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

    private boolean installed(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void launchMineVr() {
        if (installed("com.minevr.bridge")) {
            Intent bridge = new Intent("com.minevr.bridge.START");
            bridge.setPackage("com.minevr.bridge");
            try {
                startActivity(bridge);
                nexaView.setMessage("MineVR Bridge iniciado");
                return;
            } catch (Exception ignored) {
                try {
                    sendBroadcast(bridge);
                    nexaView.setMessage("Sinal enviado ao MineVR Bridge");
                    return;
                } catch (Exception ignoredAgain) { }
            }
        }
        launchPackage("com.qcxr.qcxr", "QuestCraft/MineVR nao encontrado");
    }

    private void launchPackage(String packageName, String missingMessage) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            nexaView.setMessage(missingMessage);
            return;
        }
        try {
            startActivity(launch);
            nexaView.setMessage("Abrindo " + packageName);
        } catch (Exception e) {
            nexaView.setMessage("Falha ao abrir " + packageName);
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

            paint.setColor(Color.rgb(11, 20, 34));
            c.drawRect(offsetX, 0, offsetX + eyeW, h, paint);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(Color.WHITE);
            paint.setTextSize(Math.max(24f, eyeW * 0.045f));
            paint.setFakeBoldText(true);
            c.drawText("NEXA XR OS 23", cx - parallaxX, h * 0.16f - parallaxY, paint);

            paint.setFakeBoldText(false);
            paint.setTextSize(Math.max(13f, eyeW * 0.020f));
            paint.setColor(Color.rgb(155, 190, 225));
            c.drawText(String.format("HEAD  yaw %.1f  pitch %.1f  roll %.1f", yaw, pitch, roll),
                    cx - parallaxX, h * 0.22f - parallaxY, paint);

            drawCard(c, offsetX, eyeW, h, 0, "MINEVR", mineVrStatus());
            drawCard(c, offsetX, eyeW, h, 1, "ROBLOX", installed("com.roblox.client") ? "INSTALADO" : "NAO INSTALADO");
            drawCard(c, offsetX, eyeW, h, 2, "RECENTRALIZAR", "HEAD POSE");

            paint.setTextSize(Math.max(13f, eyeW * 0.021f));
            paint.setColor(Color.rgb(210, 220, 235));
            c.drawText(message, cx, h * 0.91f, paint);

            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(2.5f);
            c.drawLine(cx - 10, h * 0.5f, cx + 10, h * 0.5f, paint);
            c.drawLine(cx, h * 0.5f - 10, cx, h * 0.5f + 10, paint);
        }

        private String mineVrStatus() {
            if (installed("com.minevr.bridge")) return "BRIDGE INSTALADO";
            if (installed("com.qcxr.qcxr")) return "QUESTCRAFT INSTALADO";
            return "NAO INSTALADO";
        }

        private void drawCard(Canvas c, int offsetX, int eyeW, int h, int index, String title, String subtitle) {
            float left = offsetX + eyeW * 0.16f;
            float right = offsetX + eyeW * 0.84f;
            float top = h * (0.31f + index * 0.17f);
            float bottom = top + h * 0.12f;

            paint.setColor(Color.rgb(20, 38, 58));
            c.drawRoundRect(new RectF(left, top, right, bottom), 22f, 22f, paint);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            paint.setTextSize(Math.max(17f, eyeW * 0.027f));
            paint.setColor(Color.WHITE);
            c.drawText(title, (left + right) * 0.5f, top + (bottom - top) * 0.47f, paint);

            paint.setFakeBoldText(false);
            paint.setTextSize(Math.max(11f, eyeW * 0.017f));
            paint.setColor(Color.rgb(130, 190, 230));
            c.drawText(subtitle, (left + right) * 0.5f, top + (bottom - top) * 0.73f, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            int h = getHeight();
            float y = event.getY();
            if (y >= h * 0.29f && y < h * 0.46f) {
                launchMineVr();
            } else if (y >= h * 0.46f && y < h * 0.63f) {
                launchPackage("com.roblox.client", "Roblox nao encontrado");
            } else if (y >= h * 0.63f && y < h * 0.80f) {
                yaw = 0f;
                pitch = 0f;
                roll = 0f;
                setMessage("Head pose recentralizada");
            }
            return true;
        }
    }
}
