package pojlib;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.Image;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.view.Surface;
import android.view.WindowManager;
import android.os.SystemClock;

import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

public final class NexaHandTracker implements AutoCloseable {
    public static final class HandFrame {
        public static final HandFrame EMPTY = new HandFrame(0, new float[126], ones(), hiddenCursors());
        public final int validMask;
        public final float[] joints;
        public final float[] pinch;
        /** Stable PhoneXR-style aim point per hand: RIGHT x/y, LEFT x/y in display 0..1. */
        public final float[] cursor;
        HandFrame(int validMask, float[] joints, float[] pinch, float[] cursor) {
            this.validMask = validMask; this.joints = joints; this.pinch = pinch; this.cursor = cursor;
        }
        private static float[] ones() { float[] v = new float[8]; Arrays.fill(v, 1f); return v; }
        private static float[] hiddenCursors() { float[] v = new float[4]; Arrays.fill(v, -1f); return v; }
    }

    private HandLandmarker detector;
    private final float palmWidthMeters;
    private final Context context;
    private final int rearSensorOrientation;
    private long lastRun;
    private long latestAt;
    private HandFrame latest = HandFrame.EMPTY;
    private Bitmap bitmap;
    private int[] rgb;
    private byte[][] planeBytes = new byte[3][];

    public NexaHandTracker(Context context, int maxHands, boolean preferGpu, float palmWidthMeters) {
        this.context = context.getApplicationContext();
        this.palmWidthMeters = palmWidthMeters;
        this.rearSensorOrientation = findRearSensorOrientation(this.context);
        try {
            detector = create(context, maxHands, preferGpu ? Delegate.GPU : Delegate.CPU);
        } catch (Throwable gpuFailure) {
            detector = create(context, maxHands, Delegate.CPU);
        }
    }

    private static HandLandmarker create(Context context, int maxHands, Delegate delegate) {
        HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(delegate).build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(maxHands)
                .setMinHandDetectionConfidence(0.36f)
                .setMinHandPresenceConfidence(0.35f)
                .setMinTrackingConfidence(0.34f)
                .build();
        return HandLandmarker.createFromOptions(context.getApplicationContext(), options);
    }

    public HandFrame process(Frame frame) {
        long now = SystemClock.uptimeMillis();
        if (now - lastRun < 22) return latest;
        lastRun = now;
        Image image = null;
        try {
            image = frame.acquireCameraImage();
            Rect crop = image.getCropRect();
            int step = Math.max(1, (int)Math.ceil(crop.width() / 384.0));
            int w = Math.max(1, crop.width() / step);
            int h = Math.max(1, crop.height() / step);
            ensureBitmap(w, h);
            yuvToRgb(image, crop, step, w, h);
            bitmap.setPixels(rgb, 0, w, 0, 0, w, h);

            MPImage input = new BitmapImageBuilder(bitmap).build();
            try {
                int rotation = getImageRotationDegrees();
                ImageProcessingOptions ipo = ImageProcessingOptions.builder().setRotationDegrees(rotation).build();
                HandLandmarkerResult result = detector.detectForVideo(input, ipo, now);
                HandFrame next = toWorld(frame, crop, rotation, result);
                latest = next; latestAt = now;
                return next;
            } finally {
                input.close();
            }
        } catch (Throwable ignored) {
            return now - latestAt < 280 ? latest : HandFrame.EMPTY;
        } finally {
            if (image != null) image.close();
        }
    }

    private HandFrame toWorld(Frame frame, Rect crop, int rotation, HandLandmarkerResult result) {
        float[] out = new float[126];
        float[] pinch = new float[8]; Arrays.fill(pinch, 1f);
        float[] cursor = new float[4]; Arrays.fill(cursor, -1f);
        int mask = 0;
        List<List<NormalizedLandmark>> all = result.landmarks();
        List<List<Category>> handed = result.handedness();
        CameraIntrinsics intr = frame.getCamera().getImageIntrinsics();
        float fx = intr.getFocalLength()[0], fy = intr.getFocalLength()[1];
        float cx = intr.getPrincipalPoint()[0], cy = intr.getPrincipalPoint()[1];
        Pose cam = frame.getCamera().getPose();

        for (int n = 0; n < all.size() && n < 2; n++) {
            List<NormalizedLandmark> lm = all.get(n);
            if (lm.size() < 21) continue;
            String side = (n < handed.size() && !handed.get(n).isEmpty()) ? handed.get(n).get(0).categoryName() : "";
            int id = "Right".equalsIgnoreCase(side) ? 0 : "Left".equalsIgnoreCase(side) ? 1 : n;

            // PhoneXR's stable cursor: mostly the thumb/index bases, lightly following the
            // fingertip midpoint. It stays near the pinch point without jumping as the pinch closes.
            NormalizedLandmark thumbTip = lm.get(4);
            NormalizedLandmark indexTip = lm.get(8);
            NormalizedLandmark thumbBase = lm.get(2);
            NormalizedLandmark indexBase = lm.get(5);
            float pinchX = (thumbTip.x() + indexTip.x()) * 0.5f;
            float pinchY = (thumbTip.y() + indexTip.y()) * 0.5f;
            cursor[id * 2] = clamp(thumbBase.x() * 0.30f + indexBase.x() * 0.45f + pinchX * 0.25f, 0f, 1f);
            cursor[id * 2 + 1] = clamp(thumbBase.y() * 0.30f + indexBase.y() * 0.45f + pinchY * 0.25f, 0f, 1f);

            NormalizedLandmark iMcp = lm.get(5), pMcp = lm.get(17);
            float[] iRaw = unrotate(iMcp.x(), iMcp.y(), rotation);
            float[] pRaw = unrotate(pMcp.x(), pMcp.y(), rotation);
            float dx = (iRaw[0] - pRaw[0]) * crop.width();
            float dy = (iRaw[1] - pRaw[1]) * crop.height();
            float palmPx = Math.max(8f, (float)Math.sqrt(dx*dx + dy*dy));
            float f = (fx + fy) * 0.5f;
            float depth = clamp((f * palmWidthMeters) / palmPx, 0.15f, 1.8f);

            for (int j = 0; j < 21; j++) {
                NormalizedLandmark p = lm.get(j);
                float[] raw = unrotate(p.x(), p.y(), rotation);
                float u = crop.left + raw[0] * crop.width();
                float v = crop.top + raw[1] * crop.height();
                float z = clamp(depth + p.z() * palmWidthMeters * 3.0f, 0.10f, 2.2f);
                float xc = (u - cx) * z / fx;
                float yc = -((v - cy) * z / fy);
                float[] world = cam.transformPoint(new float[]{xc, yc, -z});
                int k = id * 63 + j * 3;
                out[k] = world[0]; out[k+1] = world[1]; out[k+2] = world[2];
            }
            int[] tips = {8,12,16,20};
            for (int fidx = 0; fidx < 4; fidx++) {
                pinch[id*4+fidx] = distance(out, id, 4, tips[fidx]) / Math.max(0.03f, palmWidthMeters);
            }
            mask |= (1 << id);
        }
        return new HandFrame(mask, out, pinch, cursor);
    }

    private int getImageRotationDegrees() {
        int displayDegrees = 0;
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            int r = wm.getDefaultDisplay().getRotation();
            if (r == Surface.ROTATION_90) displayDegrees = 90;
            else if (r == Surface.ROTATION_180) displayDegrees = 180;
            else if (r == Surface.ROTATION_270) displayDegrees = 270;
        } catch (Throwable ignored) {}
        return (rearSensorOrientation - displayDegrees + 360) % 360;
    }

    private static int findRearSensorOrientation(Context context) {
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                Integer sensor = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK && sensor != null) return sensor;
            }
        } catch (Throwable ignored) {}
        return 90;
    }

    private static float[] unrotate(float x, float y, int degrees) {
        switch ((degrees % 360 + 360) % 360) {
            case 90:  return new float[]{y, 1f - x};
            case 180: return new float[]{1f - x, 1f - y};
            case 270: return new float[]{1f - y, x};
            default:  return new float[]{x, y};
        }
    }

    private static float distance(float[] a, int hand, int ja, int jb) {
        int x = hand*63 + ja*3, y = hand*63 + jb*3;
        float dx=a[x]-a[y], dy=a[x+1]-a[y+1], dz=a[x+2]-a[y+2];
        return (float)Math.sqrt(dx*dx+dy*dy+dz*dz);
    }

    private void ensureBitmap(int w, int h) {
        if (bitmap == null || bitmap.getWidth()!=w || bitmap.getHeight()!=h) {
            if (bitmap != null) bitmap.recycle();
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            rgb = new int[w*h];
        }
    }

    private void yuvToRgb(Image image, Rect crop, int step, int width, int height) {
        Image.Plane[] p = image.getPlanes();
        for (int i=0;i<3;i++) {
            ByteBuffer src=p[i].getBuffer().duplicate();
            int len=src.remaining();
            if (planeBytes[i]==null || planeBytes[i].length<len) planeBytes[i]=new byte[len];
            src.get(planeBytes[i],0,len);
        }
        byte[] y=planeBytes[0], u=planeBytes[1], v=planeBytes[2];
        int ys=p[0].getRowStride(), us=p[1].getRowStride(), vs=p[2].getRowStride();
        int yp=p[0].getPixelStride(), up=p[1].getPixelStride(), vp=p[2].getPixelStride();
        for(int row=0;row<height;row++) {
            int py=crop.top+row*step, yr=py*ys, ur=(py/2)*us, vr=(py/2)*vs;
            for(int col=0;col<width;col++) {
                int px=crop.left+col*step;
                int yy=(y[yr+px*yp]&255)-16;
                int uu=(u[ur+(px/2)*up]&255)-128;
                int vv=(v[vr+(px/2)*vp]&255)-128;
                int rr=clamp255(((yy*298)+(vv*409)+128)>>8);
                int gg=clamp255(((yy*298)-(uu*100)-(vv*208)+128)>>8);
                int bb=clamp255(((yy*298)+(uu*516)+128)>>8);
                rgb[row*width+col]=0xFF000000|(rr<<16)|(gg<<8)|bb;
            }
        }
    }

    private static int clamp255(int v){return Math.max(0,Math.min(255,v));}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}

    @Override public void close() {
        detector.close();
        if (bitmap != null) bitmap.recycle();
    }
}
