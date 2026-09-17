package pojlib;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.opengl.GLES20;
import android.opengl.GLES11Ext;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraConfig;
import com.google.ar.core.CameraConfigFilter;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;

import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Dedicated tiny GL surface that drives ARCore independently of Unity/Minecraft. */
public final class NexaTrackingSurface extends GLSurfaceView implements GLSurfaceView.Renderer {
    public static final int CAMERA_REQUEST = 4712;

    private final Activity activity;
    private Session session;
    private NexaHandTracker hands;
    private int cameraTexture;
    private volatile boolean running;
    private volatile boolean hostResumed;

    public NexaTrackingSurface(Activity activity) {
        super(activity);
        this.activity = activity;
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setAlpha(0.01f);
        NexaXRBridge.start();
    }

    public boolean prepare() {
        if (activity.checkSelfPermission("android.permission.CAMERA") != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{"android.permission.CAMERA"}, CAMERA_REQUEST);
            return false;
        }
        try {
            ArCoreApk.Availability availability = ArCoreApk.getInstance().checkAvailability(activity);
            if (availability.isTransient()) {
                postDelayed(() -> {
                    if (hostResumed && !running) onResume();
                }, 500L);
                return false;
            }
            if (!availability.isSupported()) return false;
            if (ArCoreApk.getInstance().requestInstall(activity, true) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) return false;

            if (session == null) {
                session = new Session(activity);
                CameraConfigFilter rear = new CameraConfigFilter(session)
                        .setFacingDirection(CameraConfig.FacingDirection.BACK);
                List<CameraConfig> rearConfigs = session.getSupportedCameraConfigs(rear);
                if (rearConfigs.isEmpty()) throw new IllegalStateException("No rear ARCore camera config");
                session.setCameraConfig(rearConfigs.get(0));

                Config cfg = new Config(session);
                cfg.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
                cfg.setFocusMode(Config.FocusMode.AUTO);
                cfg.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);
                cfg.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
                session.configure(cfg);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public void onResume() {
        hostResumed = true;
        if (!prepare()) return;
        try {
            session.resume();
            running = true;
            super.onResume();
        } catch (Throwable ignored) {}
    }

    @Override public void onPause() {
        hostResumed = false;
        running = false;
        try { if (session != null) session.pause(); } catch (Throwable ignored) {}
        super.onPause();
    }

    public void shutdown() {
        running = false;
        try { if (hands != null) hands.close(); } catch (Throwable ignored) {}
        hands = null;
        try { if (session != null) session.close(); } catch (Throwable ignored) {}
        session = null;
        NexaXRBridge.stop();
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        if (hands == null) hands = new NexaHandTracker(activity, 2, true, 0.07f);
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        cameraTexture = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
        if (session == null) return;
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        session.setDisplayGeometry(rotation, Math.max(1, width), Math.max(1, height));
    }

    @Override public void onDrawFrame(GL10 gl) {
        if (!running || session == null || cameraTexture == 0) return;
        try {
            session.setCameraTextureName(cameraTexture);
            Frame frame = session.update();
            Camera camera = frame.getCamera();
            Pose p = camera.getDisplayOrientedPose();
            float[] t = p.getTranslation();
            float[] q = p.getRotationQuaternion();
            boolean tracked = camera.getTrackingState() == TrackingState.TRACKING;

            NexaHandTracker.HandFrame hf = (!tracked || hands == null) ? NexaHandTracker.HandFrame.EMPTY : hands.process(frame);
            NexaXRBridge.updateTracking(
                    tracked,
                    new float[]{t[0], t[1], t[2], q[0], q[1], q[2], q[3]},
                    hf.validMask,
                    hf.joints,
                    hf.pinch,
                    SystemClock.uptimeMillis());
        } catch (Throwable ignored) {
        }
    }
}
