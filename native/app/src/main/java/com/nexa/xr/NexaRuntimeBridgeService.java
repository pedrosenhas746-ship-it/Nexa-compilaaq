package com.nexa.xr;

import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;

public final class NexaRuntimeBridgeService extends Service implements SensorEventListener {
    public static final int MSG_GET_STATE = 1;
    public static final int MSG_GET_CAPS = 2;

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private volatile float yaw;
    private volatile float pitch;
    private volatile float roll;

    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.replyTo == null) return;
            try {
                if (msg.what == MSG_GET_STATE) {
                    Bundle b = new Bundle();
                    b.putFloat("yaw", yaw);
                    b.putFloat("pitch", pitch);
                    b.putFloat("roll", roll);
                    b.putLong("timestampMs", System.currentTimeMillis());
                    Message reply = Message.obtain(null, MSG_GET_STATE);
                    reply.setData(b);
                    msg.replyTo.send(reply);
                } else if (msg.what == MSG_GET_CAPS) {
                    UniversalRuntimeManager.DeviceCaps caps =
                            new UniversalRuntimeManager(NexaRuntimeBridgeService.this).getDeviceCaps();
                    Bundle b = new Bundle();
                    b.putBoolean("arm64", caps.arm64);
                    b.putBoolean("gyro", caps.gyro);
                    b.putBoolean("rotationVector", caps.rotationVector);
                    b.putBoolean("vulkan", caps.vulkan);
                    b.putInt("glEsVersion", caps.glEsVersion);
                    Message reply = Message.obtain(null, MSG_GET_CAPS);
                    reply.setData(b);
                    msg.replyTo.send(reply);
                }
            } catch (Exception ignored) {
            }
        }
    });

    @Override
    public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (rotationSensor != null) {
                sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        float[] rotation = new float[9];
        float[] orientation = new float[3];
        SensorManager.getRotationMatrixFromVector(rotation, event.values);
        SensorManager.getOrientation(rotation, orientation);
        yaw = (float) Math.toDegrees(orientation[0]);
        pitch = (float) Math.toDegrees(orientation[1]);
        roll = (float) Math.toDegrees(orientation[2]);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}

