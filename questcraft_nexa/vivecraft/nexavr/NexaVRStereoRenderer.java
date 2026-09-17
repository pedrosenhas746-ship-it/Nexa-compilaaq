package org.vivecraft.client_vr.provider.nexavr;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.util.Mth;
import net.minecraft.util.Tuple;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.vivecraft.client_vr.VRTextureTarget;
import org.vivecraft.client_vr.provider.VRRenderer;
import org.vivecraft.client_vr.render.RenderPass;
import org.vivecraft.client_vr.render.helpers.RenderHelper;
import org.vivecraft.client_vr.settings.VRSettings;

/** Simple two-eye framebuffer renderer. Final SBS composition is handled by Vivecraft's DUAL mirror path. */
public final class NexaVRStereoRenderer extends VRRenderer {
    private int leftTextureId = -1;
    private int rightTextureId = -1;
    private RenderTarget leftEye;
    private RenderTarget rightEye;

    public NexaVRStereoRenderer(NexaVR vr) {
        super(vr);
    }

    @Override
    public Tuple<Integer, Integer> getRenderTextureSizes() {
        if (this.resolution == null) {
            int width = intProperty("nexa.eyeWidth", 1024, 640, 2048);
            int height = intProperty("nexa.eyeHeight", 1024, 640, 2048);
            this.resolution = new Tuple<>(width, height);
            this.ss = -1.0F;
            VRSettings.LOGGER.info("Vivecraft: Nexa eye render size {}x{}", width, height);
        }
        return this.resolution;
    }

    @Override
    protected Matrix4f getProjectionMatrix(int eyeType, float nearClip, float farClip) {
        float fov = floatProperty("nexa.fov", 82.0F, 55.0F, 115.0F);
        return new Matrix4f().setPerspective(fov * Mth.DEG_TO_RAD, 1.0F, nearClip, farClip);
    }

    @Override
    public void createRenderTexture(int width, int height) {
        int previousTexture = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);

        this.leftTextureId = createTexture(width, height);
        this.rightTextureId = createTexture(width, height);
        this.lastError = RenderHelper.checkGLError("create Nexa eye textures");

        this.leftEye = new VRTextureTarget("Nexa L Eye", width, height, false,
            this.leftTextureId, true, false, false);
        String leftError = RenderHelper.checkGLError("Nexa left eye framebuffer setup");

        this.rightEye = new VRTextureTarget("Nexa R Eye", width, height, false,
            this.rightTextureId, true, false, false);
        String rightError = RenderHelper.checkGLError("Nexa right eye framebuffer setup");

        if (this.lastError.isEmpty()) {
            this.lastError = !leftError.isEmpty() ? leftError : rightError;
        }
        RenderSystem.bindTexture(previousTexture);
    }

    private static int createTexture(int width, int height) {
        int id = GlStateManager._genTexture();
        RenderSystem.bindTexture(id);
        RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP_TO_EDGE);
        RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null);
        return id;
    }

    @Override
    public void endFrame() {
        // No external XR runtime. MinecraftVRMixin mirrors LEFT/RIGHT to the Android window in SBS.
    }

    @Override public boolean providesStencilMask() { return false; }
    @Override public RenderTarget getLeftEyeTarget() { return this.leftEye; }
    @Override public RenderTarget getRightEyeTarget() { return this.rightEye; }
    @Override public float[] getStencilMask(RenderPass eye) { return null; }
    @Override public String getName() { return "NexaVR"; }

    @Override
    public void destroy() {
        super.destroyBuffers();
        super.destroy();
        if (this.leftEye != null) {
            this.leftEye.destroyBuffers();
            this.leftEye = null;
        }
        if (this.rightEye != null) {
            this.rightEye.destroyBuffers();
            this.rightEye = null;
        }
        if (this.leftTextureId > -1) {
            TextureUtil.releaseTextureId(this.leftTextureId);
            this.leftTextureId = -1;
        }
        if (this.rightTextureId > -1) {
            TextureUtil.releaseTextureId(this.rightTextureId);
            this.rightTextureId = -1;
        }
    }

    private static int intProperty(String key, int fallback, int lo, int hi) {
        try { return Math.max(lo, Math.min(hi, Integer.parseInt(System.getProperty(key, String.valueOf(fallback))))); }
        catch (Throwable ignored) { return fallback; }
    }

    private static float floatProperty(String key, float fallback, float lo, float hi) {
        try { return Math.max(lo, Math.min(hi, Float.parseFloat(System.getProperty(key, String.valueOf(fallback))))); }
        catch (Throwable ignored) { return fallback; }
    }
}