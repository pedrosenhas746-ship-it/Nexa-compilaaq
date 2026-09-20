package pojlib;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nexa XR transport for QuestCraft.
 *
 * ART (Android launcher/tracker) and the Minecraft JVM are separate Java VMs,
 * so this bridge intentionally uses a tiny loopback UDP packet instead of trying
 * to call Android classes directly from Vivecraft. UDP is local-only and keeps
 * only the newest tracking sample relevant.
 */
public final class NexaXRBridge {
    public static final int PORT = 47821;
    public static final int MAGIC = 0x4E585231; // "NXR1"
    public static final int VERSION = 1;

    private static final Object LOCK = new Object();
    private static final float[] HEAD = new float[]{0, 1.62f, 0, 0, 0, 0, 1};
    private static final float[] JOINTS = new float[126]; // 2 * 21 * xyz
    private static final float[] PINCH = new float[8];
    /** Local-only PhoneXR-style cursor points: RIGHT x/y, LEFT x/y. Not added to UDP v1. */
    private static final float[] CURSOR = new float[]{-1f, -1f, -1f, -1f};
    private static int validHands;
    private static boolean headTracked;
    private static long trackingTimestampMs;

    // Physical controller state. This coexists with hand/head 6DoF; there is no mode switch.
    private static float leftX, leftY, rightX, rightY, leftTrigger, rightTrigger;
    private static boolean leftTriggerKeyDown, rightTriggerKeyDown;
    private static int buttons;

    public static final int BTN_A = 1 << 0;
    public static final int BTN_B = 1 << 1;
    public static final int BTN_X = 1 << 2;
    public static final int BTN_Y = 1 << 3;
    public static final int BTN_L1 = 1 << 4;
    public static final int BTN_R1 = 1 << 5;
    public static final int BTN_L2 = 1 << 6;
    public static final int BTN_R2 = 1 << 7;
    public static final int BTN_L3 = 1 << 8;
    public static final int BTN_R3 = 1 << 9;
    public static final int BTN_START = 1 << 10;
    public static final int BTN_SELECT = 1 << 11;

    private static DatagramSocket socket;
    private static InetAddress loopback;
    private static final AtomicBoolean started = new AtomicBoolean(false);

    private NexaXRBridge() {}

    /** Immutable copy for the native Android lodge renderer. */
    public static final class LocalSnapshot {
        public final boolean headTracked;
        public final long trackingTimestampMs;
        public final int validHands;
        public final float[] head;
        public final float[] joints;
        public final float[] pinch;
        public final float[] cursor;

        LocalSnapshot(boolean headTracked, long trackingTimestampMs, int validHands,
                      float[] head, float[] joints, float[] pinch, float[] cursor) {
            this.headTracked = headTracked;
            this.trackingTimestampMs = trackingTimestampMs;
            this.validHands = validHands;
            this.head = head;
            this.joints = joints;
            this.pinch = pinch;
            this.cursor = cursor;
        }
    }

    public static LocalSnapshot getLocalSnapshot() {
        synchronized (LOCK) {
            return new LocalSnapshot(
                    headTracked,
                    trackingTimestampMs,
                    validHands,
                    HEAD.clone(),
                    JOINTS.clone(),
                    PINCH.clone(),
                    CURSOR.clone());
        }
    }

    public static void start() {
        if (!started.compareAndSet(false, true)) return;
        try {
            socket = new DatagramSocket();
            loopback = InetAddress.getByName("127.0.0.1");
            Arrays.fill(PINCH, 1.0f);
        } catch (Exception e) {
            started.set(false);
            socket = null;
        }
    }

    public static void stop() {
        started.set(false);
        DatagramSocket s = socket;
        socket = null;
        if (s != null) s.close();
    }

    public static void updateTracking(boolean tracked, float[] head, int handMask,
                                      float[] joints, float[] pinch, long timestampMs) {
        updateTracking(tracked, head, handMask, joints, pinch, null, timestampMs);
    }

    public static void updateTracking(boolean tracked, float[] head, int handMask,
                                      float[] joints, float[] pinch, float[] cursor, long timestampMs) {
        start();
        synchronized (LOCK) {
            headTracked = tracked;
            trackingTimestampMs = timestampMs;
            validHands = handMask;
            if (head != null && head.length >= 7) System.arraycopy(head, 0, HEAD, 0, 7);
            if (joints != null && joints.length >= 126) System.arraycopy(joints, 0, JOINTS, 0, 126);
            if (pinch != null && pinch.length >= 8) System.arraycopy(pinch, 0, PINCH, 0, 8);
            if (cursor != null && cursor.length >= 4) System.arraycopy(cursor, 0, CURSOR, 0, 4);
            else Arrays.fill(CURSOR, -1f);
        }
        sendLatest();
    }

    public static void onKeyEvent(KeyEvent e) {
        if (!isGamepad(e.getSource())) return;
        int bit = keyBit(e.getKeyCode());
        if (bit == 0) return;
        synchronized (LOCK) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) buttons |= bit;
            else if (e.getAction() == KeyEvent.ACTION_UP) buttons &= ~bit;
            if (bit == BTN_L2) leftTriggerKeyDown = e.getAction() == KeyEvent.ACTION_DOWN;
            if (bit == BTN_R2) rightTriggerKeyDown = e.getAction() == KeyEvent.ACTION_DOWN;
            if (bit == BTN_L2) leftTrigger = leftTriggerKeyDown ? 1f : 0f;
            if (bit == BTN_R2) rightTrigger = rightTriggerKeyDown ? 1f : 0f;
        }
        sendLatest();
    }

    public static void onMotionEvent(MotionEvent e) {
        if (!isGamepad(e.getSource())) return;
        InputDevice dev = e.getDevice();
        synchronized (LOCK) {
            leftX = axis(e, dev, MotionEvent.AXIS_X);
            leftY = -axis(e, dev, MotionEvent.AXIS_Y);
            rightX = axis(e, dev, MotionEvent.AXIS_Z);
            rightY = -axis(e, dev, MotionEvent.AXIS_RZ);
            leftTrigger = Math.max(trigger(e, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE), leftTriggerKeyDown ? 1f : 0f);
            rightTrigger = Math.max(trigger(e, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS), rightTriggerKeyDown ? 1f : 0f);
        }
        sendLatest();
    }

    private static boolean isGamepad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private static float axis(MotionEvent e, InputDevice dev, int axis) {
        float v = e.getAxisValue(axis);
        if (dev == null) return deadzone(v, 0.12f);
        InputDevice.MotionRange r = dev.getMotionRange(axis, e.getSource());
        float flat = r == null ? 0.12f : Math.max(0.08f, r.getFlat());
        return deadzone(v, flat);
    }

    private static float trigger(MotionEvent e, int primary, int fallback) {
        float a = e.getAxisValue(primary);
        float b = e.getAxisValue(fallback);
        return clamp(Math.max(a, b), 0f, 1f);
    }

    private static float deadzone(float v, float dz) {
        float a = Math.abs(v);
        if (a <= dz) return 0f;
        float n = (a - dz) / Math.max(0.0001f, 1f - dz);
        return Math.copySign(clamp(n, 0f, 1f), v);
    }

    private static int keyBit(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_A: return BTN_A;
            case KeyEvent.KEYCODE_BUTTON_B: return BTN_B;
            case KeyEvent.KEYCODE_BUTTON_X: return BTN_X;
            case KeyEvent.KEYCODE_BUTTON_Y: return BTN_Y;
            case KeyEvent.KEYCODE_BUTTON_L1: return BTN_L1;
            case KeyEvent.KEYCODE_BUTTON_R1: return BTN_R1;
            case KeyEvent.KEYCODE_BUTTON_L2: return BTN_L2;
            case KeyEvent.KEYCODE_BUTTON_R2: return BTN_R2;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return BTN_L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return BTN_R3;
            case KeyEvent.KEYCODE_BUTTON_START: return BTN_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return BTN_SELECT;
            default: return 0;
        }
    }

    private static void sendLatest() {
        DatagramSocket s = socket;
        InetAddress addr = loopback;
        if (!started.get() || s == null || addr == null) return;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(700);
            DataOutputStream out = new DataOutputStream(bos);
            synchronized (LOCK) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeLong(System.nanoTime());
                out.writeLong(trackingTimestampMs);
                out.writeBoolean(headTracked);
                out.writeByte(validHands);
                for (float v : HEAD) out.writeFloat(v);
                for (float v : JOINTS) out.writeFloat(v);
                for (float v : PINCH) out.writeFloat(v);
                out.writeFloat(leftX); out.writeFloat(leftY);
                out.writeFloat(rightX); out.writeFloat(rightY);
                out.writeFloat(leftTrigger); out.writeFloat(rightTrigger);
                out.writeInt(buttons);
            }
            out.flush();
            byte[] data = bos.toByteArray();
            s.send(new DatagramPacket(data, data.length, addr, PORT));
        } catch (Exception ignored) {
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
