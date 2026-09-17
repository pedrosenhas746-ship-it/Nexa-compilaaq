package org.vivecraft.client_vr.provider.nexavr;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.vivecraft.client.VivecraftVRMod;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.provider.ControllerType;
import org.vivecraft.client_vr.provider.HapticScheduler;
import org.vivecraft.client_vr.provider.MCVR;
import org.vivecraft.client_vr.provider.VRRenderer;
import org.vivecraft.client_vr.provider.control.VRInputAction;
import org.vivecraft.client_vr.settings.VRSettings;

import java.util.List;

/**
 * Vivecraft provider backed by Nexa's Android ARCore + MediaPipe loopback bridge.
 * RIGHT controller/hand = 0, LEFT controller/hand = 1, matching MCVR.
 * Physical Android gamepad input stays in Pojlib; this provider does not synthesize or release those keys.
 */
public final class NexaVR extends MCVR {
    private static final long TRACKING_MAX_AGE_MS = 350L;
    private static final float DEFAULT_EYE_HEIGHT = 1.62F;

    private static NexaVR INSTANCE;
    private final NexaTrackingReceiver receiver = new NexaTrackingReceiver();
    private final Vector3f baseHeadPosition = new Vector3f();
    private boolean baseHeadCaptured;
    private boolean active = true;
    private float ipd = 0.064F;

    private final Vector3f[] filteredHandPosition = {new Vector3f(), new Vector3f()};
    private final Quaternionf[] filteredHandRotation = {new Quaternionf(), new Quaternionf()};
    private final boolean[] handFilterReady = new boolean[2];

    public NexaVR(Minecraft mc, ClientDataHolderVR dh) {
        super(mc, dh, VivecraftVRMod.INSTANCE);
        INSTANCE = this;
        this.hapticScheduler = new HapticScheduler() {
            @Override
            public void queueHapticPulse(ControllerType controller, float durationSeconds,
                                         float frequency, float amplitude, float delaySeconds) {
                // Phone/VRBox hand tracking has no controller haptics. Keep this intentionally silent.
            }
        };
    }

    public static NexaVR get() {
        return INSTANCE;
    }

    @Override
    public boolean init() {
        if (this.initialized) return true;
        this.mc = Minecraft.getInstance();
        this.ipd = floatProperty("nexa.ipd", 0.064F, 0.050F, 0.080F);
        this.receiver.start();
        this.populateInputActions();
        this.headIsTracking = false;
        this.hmdPose.identity().translate(0.0F, DEFAULT_EYE_HEIGHT, 0.0F);
        updateEyePoses();
        this.initialized = true;
        this.initSuccess = true;
        this.initStatus = "Nexa ARCore/MediaPipe bridge ready";
        VRSettings.LOGGER.info("Vivecraft: NexaVR initialized (IPD {} m)", this.ipd);
        return true;
    }

    @Override
    public void destroy() {
        this.receiver.close();
        this.initialized = false;
        this.initSuccess = false;
        this.baseHeadCaptured = false;
        super.destroy();
    }

    @Override
    public String getName() {
        return "NexaVR";
    }

    @Override
    public void handleEvents() {
        // No external XR event queue.
    }

    @Override
    public void poll(long frameIndex) {
        if (!this.initialized) return;
        NexaTrackingReceiver.Snapshot s = this.receiver.latest();
        boolean fresh = s != null && s.isFresh(TRACKING_MAX_AGE_MS);

        if (fresh && s.headTracked) {
            applyHead(s.head);
            this.headIsTracking = true;
        } else {
            this.headIsTracking = false;
        }

        if (fresh) {
            applyHand(s, RIGHT_CONTROLLER);
            applyHand(s, LEFT_CONTROLLER);
        } else {
            this.controllerTracking[RIGHT_CONTROLLER] = false;
            this.controllerTracking[LEFT_CONTROLLER] = false;
        }

        this.updateAim();
        this.hmdSampling();
    }

    private void applyHead(float[] head) {
        if (head == null || head.length < 7) return;
        Vector3f raw = new Vector3f(head[0], head[1], head[2]);
        if (!this.baseHeadCaptured) {
            this.baseHeadPosition.set(raw);
            this.baseHeadCaptured = true;
        }
        Vector3f pos = raw.sub(this.baseHeadPosition, new Vector3f());
        pos.y += floatProperty("nexa.eyeHeight", DEFAULT_EYE_HEIGHT, 1.20F, 2.10F);
        Quaternionf rot = new Quaternionf(head[3], head[4], head[5], head[6]).normalize();
        this.hmdPose.identity().rotation(rot).setTranslation(pos);
        updateEyePoses();
    }

    private void updateEyePoses() {
        this.hmdPoseLeftEye.set(this.hmdPose).translate(-this.ipd * 0.5F, 0.0F, 0.0F);
        this.hmdPoseRightEye.set(this.hmdPose).translate(this.ipd * 0.5F, 0.0F, 0.0F);
    }

    private void applyHand(NexaTrackingReceiver.Snapshot s, int hand) {
        boolean tracked = (s.handMask & (1 << hand)) != 0;
        this.controllerTracking[hand] = tracked;
        if (!tracked || !this.baseHeadCaptured) {
            this.handFilterReady[hand] = false;
            return;
        }

        Vector3f wrist = joint(s.joints, hand, 0);
        Vector3f indexMcp = joint(s.joints, hand, 5);
        Vector3f indexTip = joint(s.joints, hand, 8);
        Vector3f middleMcp = joint(s.joints, hand, 9);
        Vector3f pinkyMcp = joint(s.joints, hand, 17);

        Vector3f palm = new Vector3f(wrist).add(indexMcp).add(middleMcp).add(pinkyMcp).mul(0.25F);
        roomSpace(palm);
        roomSpace(indexTip);
        roomSpace(wrist);
        roomSpace(indexMcp);
        roomSpace(middleMcp);
        roomSpace(pinkyMcp);

        Vector3f aim = new Vector3f(indexTip).sub(indexMcp);
        if (aim.lengthSquared() < 1.0e-6F) aim.set(0, 0, -1);
        aim.normalize();

        Vector3f across = new Vector3f(indexMcp).sub(pinkyMcp);
        Vector3f towardFingers = new Vector3f(middleMcp).sub(wrist);
        Vector3f palmNormal = across.cross(towardFingers, new Vector3f());
        if (palmNormal.lengthSquared() < 1.0e-6F) palmNormal.set(0, 1, 0);
        else palmNormal.normalize();
        if (hand == LEFT_CONTROLLER) palmNormal.negate();

        Quaternionf rotation = new Quaternionf().lookAlong(aim, palmNormal).normalize();

        // Velocity-friendly EMA: stable at rest but still responsive for swings/pokes.
        float alpha = 0.68F;
        if (!this.handFilterReady[hand]) {
            this.filteredHandPosition[hand].set(palm);
            this.filteredHandRotation[hand].set(rotation);
            this.handFilterReady[hand] = true;
        } else {
            this.filteredHandPosition[hand].lerp(palm, alpha);
            this.filteredHandRotation[hand].slerp(rotation, alpha).normalize();
        }

        this.controllerPose[hand].identity()
            .rotation(this.filteredHandRotation[hand])
            .setTranslation(this.filteredHandPosition[hand]);
    }

    private void roomSpace(Vector3f v) {
        v.sub(this.baseHeadPosition);
        v.y += floatProperty("nexa.eyeHeight", DEFAULT_EYE_HEIGHT, 1.20F, 2.10F);
    }

    private static Vector3f joint(float[] joints, int hand, int joint) {
        int i = hand * 63 + joint * 3;
        return new Vector3f(joints[i], joints[i + 1], joints[i + 2]);
    }

    /** Gamepad remains owned by Pojlib; do not let empty VR actions release its keys. */
    @Override
    public void processInputs() {
    }

    @Override
    protected ControllerType findActiveBindingControllerType(KeyMapping keyMapping) {
        return null;
    }

    @Override
    public Vector2fc getPlayAreaSize() {
        return new Vector2f(2.0F, 2.0F);
    }

    @Override
    public Matrix4fc getControllerComponentTransform(int controllerIndex, String componentName) {
        return new Matrix4f();
    }

    @Override
    public List<Long> getOrigins(VRInputAction action) {
        return List.of();
    }

    @Override
    public String getOriginName(long origin) {
        return "Nexa";
    }

    @Override
    public VRRenderer createVRRenderer() {
        return new NexaVRStereoRenderer(this);
    }

    @Override
    public boolean isActive() {
        return this.active && this.initialized;
    }

    @Override
    public ControllerType getOriginControllerType(long inputValueHandle) {
        return null;
    }

    @Override
    public boolean capFPS() {
        return true;
    }

    @Override
    public float getIPD() {
        return this.ipd;
    }

    @Override
    public String getRuntimeName() {
        return "Nexa ARCore";
    }

    private static float floatProperty(String key, float fallback, float lo, float hi) {
        try {
            return Math.max(lo, Math.min(hi, Float.parseFloat(System.getProperty(key, String.valueOf(fallback)))));
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}