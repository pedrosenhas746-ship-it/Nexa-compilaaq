package com.nexa.questcraft;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Surface;
import android.view.TextureView;

import com.google.android.filament.Camera;
import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.LightManager;
import com.google.android.filament.MaterialInstance;
import com.google.android.filament.Renderer;
import com.google.android.filament.Scene;
import com.google.android.filament.Skybox;
import com.google.android.filament.SwapChain;
import com.google.android.filament.Texture;
import com.google.android.filament.TextureSampler;
import com.google.android.filament.TransformManager;
import com.google.android.filament.Viewport;
import com.google.android.filament.android.DisplayHelper;
import com.google.android.filament.android.FilamentHelper;
import com.google.android.filament.android.TextureHelper;
import com.google.android.filament.android.UiHelper;
import com.google.android.filament.gltfio.AssetLoader;
import com.google.android.filament.gltfio.FilamentAsset;
import com.google.android.filament.gltfio.Gltfio;
import com.google.android.filament.gltfio.ResourceLoader;
import com.google.android.filament.gltfio.UbershaderProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import pojlib.NexaXRBridge;

/**
 * Native Android renderer for the original QuestCraft launch lodge.
 *
 * The Unity scene is converted at build time into a compact JSON spec + GLB
 * geometry. Runtime rendering is Filament/gltfio only; no Unity runtime is
 * involved. QCWorld keeps its UVs/material slots but its Minecraft texture
 * bytes are deliberately not bundled by the build converter.
 */
public final class NexaLodgeView extends TextureView
        implements UiHelper.RendererCallback, Choreographer.FrameCallback {

    private static final long TRACKING_FRESH_MS = 250L;
    private static final long RECENTER_AFTER_LOSS_MS = 1000L;
    // Unity Built-in point-light intensity is unitless while Filament expects lumens.
    // Preserve the original Unity value in room JSON and apply one explicit calibration scale here.
    private static final float UNITY_POINT_LUMEN_SCALE = 400.0f;
    private static final float[] DEFAULT_CAMERA_POS = {-2.157f, -1.028f, 0.084f};
    // Original scene: XR Origin Y+90 degrees * Main Camera Y+90 degrees,
    // converted from Unity LH to Filament/ARCore RH.
    private static final float[] DEFAULT_CAMERA_ROT = {0f, -1f, 0f, 0f};

    static {
        Gltfio.init();
    }

    private static final class ModelNode {
        final long sceneId;
        final Long parentSceneId;
        final FilamentAsset asset;
        final float[] localMatrix;

        ModelNode(long sceneId, Long parentSceneId, FilamentAsset asset, float[] localMatrix) {
            this.sceneId = sceneId;
            this.parentSceneId = parentSceneId;
            this.asset = asset;
            this.localMatrix = localMatrix;
        }
    }

    private final AssetManager assets;
    private final Engine engine;
    private final Renderer renderer;
    private final Scene scene;
    private final com.google.android.filament.View view;
    private final Camera camera;
    private final int cameraEntity;
    private final int lightEntity;
    private final Skybox skybox;
    private final UiHelper uiHelper;
    private final DisplayHelper displayHelper;
    private final UbershaderProvider materialProvider;
    private final AssetLoader assetLoader;
    private final ResourceLoader resourceLoader;
    private final List<FilamentAsset> loadedAssets = new ArrayList<>();
    private final List<Integer> lodgeLightEntities = new ArrayList<>();
    private FilamentAsset displayAsset;
    private Texture crtMenuTexture;

    private SwapChain swapChain;
    private boolean running;
    private boolean destroyed;
    private boolean roomLoaded;

    private final float[] cameraAnchorPos = DEFAULT_CAMERA_POS.clone();
    private final float[] cameraAnchorRot = DEFAULT_CAMERA_ROT.clone();
    private final float[] lastCameraPos = DEFAULT_CAMERA_POS.clone();
    private final float[] lastCameraRot = DEFAULT_CAMERA_ROT.clone();

    private boolean trackingBaseValid;
    private final float[] baseHeadPos = new float[3];
    private final float[] baseHeadRot = new float[]{0, 0, 0, 1};
    private long lastFreshTrackingMs;

    public NexaLodgeView(Context context) {
        super(context);
        setOpaque(true);
        assets = context.getAssets();

        engine = Engine.create();
        renderer = engine.createRenderer();
        scene = engine.createScene();
        view = engine.createView();
        cameraEntity = EntityManager.get().create();
        camera = engine.createCamera(cameraEntity);
        camera.setExposure(16.0f, 1.0f / 125.0f, 100.0f);
        view.setScene(scene);
        view.setCamera(camera);

        lightEntity = EntityManager.get().create();
        new LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1.0f, 0.94f, 0.84f)
                .intensity(6_000.0f)
                .direction(-0.35f, -0.82f, -0.45f)
                .castShadows(true)
                .build(engine, lightEntity);
        scene.addEntity(lightEntity);

        skybox = new Skybox.Builder()
                .color(0.018f, 0.026f, 0.045f, 1.0f)
                .build(engine);
        scene.setSkybox(skybox);

        materialProvider = new UbershaderProvider(engine);
        assetLoader = new AssetLoader(engine, materialProvider, EntityManager.get());
        resourceLoader = new ResourceLoader(engine, true);

        displayHelper = new DisplayHelper(context);
        uiHelper = new UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK);
        uiHelper.setRenderCallback(this);
        uiHelper.attachTo(this);

        applyCamera(cameraAnchorPos, cameraAnchorRot);
        post(this::loadRoomOnce);
    }

    public void resumeRendering() {
        if (destroyed || running) return;
        running = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    public void pauseRendering() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    public boolean isRoomLoaded() {
        return roomLoaded;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running || destroyed) return;
        updateTrackedCamera();
        if (uiHelper.isReadyToRender() && swapChain != null && renderer.beginFrame(swapChain, frameTimeNanos)) {
            renderer.render(view);
            renderer.endFrame();
        }
        if (running && !destroyed) Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void onNativeWindowChanged(Surface surface) {
        if (destroyed) return;
        if (swapChain != null) engine.destroySwapChain(swapChain);
        swapChain = engine.createSwapChain(surface);
        if (getDisplay() != null) displayHelper.attach(renderer, getDisplay());
    }

    @Override
    public void onDetachedFromSurface() {
        if (destroyed) return;
        displayHelper.detach();
        if (swapChain != null) {
            engine.destroySwapChain(swapChain);
            engine.flushAndWait();
            swapChain = null;
        }
    }

    @Override
    public void onResized(int width, int height) {
        if (destroyed) return;
        int safeW = Math.max(1, width);
        int safeH = Math.max(1, height);
        double aspect = (double) safeW / (double) safeH;
        camera.setProjection(76.0, aspect, 0.05, 500.0, Camera.Fov.VERTICAL);
        view.setViewport(new Viewport(0, 0, safeW, safeH));
        FilamentHelper.synchronizePendingFrames(engine);
    }

    private void loadRoomOnce() {
        if (destroyed || roomLoaded) return;
        try {
            JSONObject spec = new JSONObject(readTextAsset("nexa/nexa-room.json"));
            resolveOriginalCamera(spec);

            List<JSONObject> modelSpecs = new ArrayList<>();
            modelSpecs.add(spec.getJSONObject("qcworld"));
            JSONArray props = spec.getJSONArray("props");
            for (int i = 0; i < props.length(); i++) modelSpecs.add(props.getJSONObject(i));
            if (modelSpecs.size() != 8) throw new IllegalStateException("Expected 8 lodge models");

            List<ModelNode> nodes = new ArrayList<>();
            Map<Long, ModelNode> bySceneId = new HashMap<>();
            for (JSONObject item : modelSpecs) {
                String runtimeModel = item.getString("runtime_model");
                FilamentAsset asset = loadGlb(runtimeModel);
                JSONObject source = item.optJSONObject("source");
                if (source != null
                        && "Assets/WinterLodge/Reality Display/Display.obj".equals(source.optString("path"))) {
                    displayAsset = asset;
                }
                long sceneId = item.getLong("scene_instance_id");
                Long parentSceneId = item.isNull("parent_instance_id")
                        ? null : item.optLong("parent_instance_id");
                ModelNode node = new ModelNode(sceneId, parentSceneId, asset, unityTrsToFilament(item));
                nodes.add(node);
                bySceneId.put(sceneId, node);
            }

            TransformManager tm = engine.getTransformManager();
            tm.openLocalTransformTransaction();
            try {
                for (ModelNode node : nodes) {
                    int rootInstance = tm.getInstance(node.asset.getRoot());
                    if (node.parentSceneId != null) {
                        ModelNode parent = bySceneId.get(node.parentSceneId);
                        if (parent == null) {
                            throw new IllegalStateException("Missing lodge parent " + node.parentSceneId);
                        }
                        int parentInstance = tm.getInstance(parent.asset.getRoot());
                        tm.setParent(rootInstance, parentInstance);
                    }
                    tm.setTransform(rootInstance, node.localMatrix);
                }
            } finally {
                tm.commitLocalTransformTransaction();
            }

            applyCrtMenuTexture("nexa/ui/rendered/main.png");
            loadOriginalLights(spec);
            roomLoaded = true;
            applyCamera(cameraAnchorPos, cameraAnchorRot);
        } catch (Throwable t) {
            android.util.Log.e("NexaLodge", "Unable to load original QuestCraft lodge", t);
        }
    }

    private void applyCrtMenuTexture(String assetPath) {
        if (displayAsset == null || destroyed) {
            android.util.Log.w("NexaLodge", "QuestCraft CRT Display.glb was not resolved");
            return;
        }
        try (InputStream in = assets.open(assetPath)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPremultiplied = true;
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
            if (bitmap == null) throw new IllegalStateException("Unable to decode " + assetPath);

            Texture texture = new Texture.Builder()
                    .width(bitmap.getWidth())
                    .height(bitmap.getHeight())
                    .levels(1)
                    .sampler(Texture.Sampler.SAMPLER_2D)
                    .format(Texture.InternalFormat.SRGB8_A8)
                    .build(engine);
            TextureHelper.setBitmap(engine, texture, 0, bitmap);

            TextureSampler sampler = new TextureSampler(
                    TextureSampler.MinFilter.LINEAR,
                    TextureSampler.MagFilter.LINEAR,
                    TextureSampler.WrapMode.CLAMP_TO_EDGE);

            MaterialInstance[] materials = displayAsset.getInstance().getMaterialInstances();
            int applied = 0;
            for (MaterialInstance material : materials) {
                try {
                    material.setParameter("baseColorMap", texture, sampler);
                    material.setParameter("baseColorFactor", 1.0f, 1.0f, 1.0f, 1.0f);
                    applied++;
                } catch (Throwable ignored) {
                    // Assimp/gltfio may select a material variant without a texture
                    // parameter. Keep trying the other primitives instead of breaking
                    // the room.
                }
            }
            if (applied == 0) {
                engine.destroyTexture(texture);
                android.util.Log.w("NexaLodge", "CRT material exposes no baseColorMap parameter");
                return;
            }
            if (crtMenuTexture != null) engine.destroyTexture(crtMenuTexture);
            crtMenuTexture = texture;
            android.util.Log.i("NexaLodge",
                    "Applied original QuestCraft CRT menu texture: " + bitmap.getWidth()
                            + "x" + bitmap.getHeight() + ", materials=" + applied);
        } catch (Throwable t) {
            android.util.Log.e("NexaLodge",
                    "Unable to apply original QuestCraft CRT menu; lodge remains usable", t);
        }
    }

    private void loadOriginalLights(JSONObject spec) throws Exception {
        JSONArray lights = spec.optJSONArray("lights");
        if (lights == null) return;
        for (int i = 0; i < lights.length(); i++) {
            JSONObject light = lights.getJSONObject(i);
            if (!light.optBoolean("active", true)) continue;
            // Unity LightType.Point == 2. Other types can be added later without
            // guessing their photometric conversion.
            if (light.optInt("type", -1) != 2) continue;
            JSONObject p = light.getJSONObject("position");
            JSONObject c = light.getJSONObject("color");
            float unityIntensity = (float) light.optDouble("intensity_unity", 1.0);
            float range = Math.max(0.1f, (float) light.optDouble("range", 10.0));
            int entity = EntityManager.get().create();
            new LightManager.Builder(LightManager.Type.POINT)
                    .color((float) c.optDouble("r", 1.0),
                           (float) c.optDouble("g", 1.0),
                           (float) c.optDouble("b", 1.0))
                    .intensity(Math.max(1.0f, unityIntensity * UNITY_POINT_LUMEN_SCALE))
                    .position((float) p.optDouble("x", 0.0),
                              (float) p.optDouble("y", 0.0),
                              -(float) p.optDouble("z", 0.0))
                    .falloff(range)
                    .castShadows(false)
                    .build(engine, entity);
            scene.addEntity(entity);
            lodgeLightEntities.add(entity);
        }
        android.util.Log.i("NexaLodge", "Loaded original QuestCraft point lights: " + lodgeLightEntities.size());
    }

    private FilamentAsset loadGlb(String path) throws Exception {
        ByteBuffer data = ByteBuffer.wrap(readBytesAsset(path));
        FilamentAsset asset = assetLoader.createAsset(data);
        if (asset == null) throw new IllegalStateException("gltfio rejected " + path);
        resourceLoader.loadResources(asset);
        asset.releaseSourceData();
        scene.addEntities(asset.getEntities());
        loadedAssets.add(asset);
        return asset;
    }

    private void resolveOriginalCamera(JSONObject spec) {
        try {
            JSONArray xr = spec.getJSONArray("xr_origin");
            Map<Long, JSONObject> objects = new HashMap<>();
            JSONObject mainCamera = null;
            for (int i = 0; i < xr.length(); i++) {
                JSONObject o = xr.getJSONObject(i);
                objects.put(o.getLong("id"), o);
                if ("Main Camera".equals(o.optString("name"))) mainCamera = o;
            }
            if (mainCamera == null) return;
            float[] p = new float[]{0, 0, 0};
            float[] q = new float[]{0, 0, 0, 1};
            float[] s = new float[]{1, 1, 1};
            composeUnityWorld(mainCamera, objects, p, q, s);
            cameraAnchorPos[0] = p[0];
            cameraAnchorPos[1] = p[1];
            cameraAnchorPos[2] = -p[2];
            cameraAnchorRot[0] = -q[0];
            cameraAnchorRot[1] = -q[1];
            cameraAnchorRot[2] = q[2];
            cameraAnchorRot[3] = q[3];
            normalizeQuat(cameraAnchorRot);
            System.arraycopy(cameraAnchorPos, 0, lastCameraPos, 0, 3);
            System.arraycopy(cameraAnchorRot, 0, lastCameraRot, 0, 4);
        } catch (Throwable ignored) {
        }
    }

    private static void composeUnityWorld(JSONObject object, Map<Long, JSONObject> objects,
                                          float[] outP, float[] outQ, float[] outS) {
        Long parentId = object.isNull("parent_id") ? null : object.optLong("parent_id");
        float[] parentP = new float[]{0, 0, 0};
        float[] parentQ = new float[]{0, 0, 0, 1};
        float[] parentS = new float[]{1, 1, 1};
        if (parentId != null && objects.containsKey(parentId)) {
            composeUnityWorld(objects.get(parentId), objects, parentP, parentQ, parentS);
        }
        float[] lp = jsonVec3(object.optJSONObject("position"), 0, 0, 0);
        float[] lq = jsonQuat(object.optJSONObject("rotation"));
        float[] ls = jsonVec3(object.optJSONObject("scale"), 1, 1, 1);
        float[] scaled = new float[]{lp[0] * parentS[0], lp[1] * parentS[1], lp[2] * parentS[2]};
        float[] rotated = rotate(parentQ, scaled);
        outP[0] = parentP[0] + rotated[0];
        outP[1] = parentP[1] + rotated[1];
        outP[2] = parentP[2] + rotated[2];
        float[] worldQ = multiply(parentQ, lq);
        System.arraycopy(worldQ, 0, outQ, 0, 4);
        outS[0] = parentS[0] * ls[0];
        outS[1] = parentS[1] * ls[1];
        outS[2] = parentS[2] * ls[2];
    }

    private void updateTrackedCamera() {
        NexaXRBridge.LocalSnapshot s = NexaXRBridge.getLocalSnapshot();
        long now = SystemClock.uptimeMillis();
        boolean fresh = s.headTracked && s.trackingTimestampMs > 0
                && now - s.trackingTimestampMs >= 0
                && now - s.trackingTimestampMs <= TRACKING_FRESH_MS
                && s.head != null && s.head.length >= 7;

        if (!fresh) {
            if (lastFreshTrackingMs > 0 && now - lastFreshTrackingMs > RECENTER_AFTER_LOSS_MS) {
                trackingBaseValid = false;
            }
            return;
        }
        lastFreshTrackingMs = now;

        float[] hp = new float[]{s.head[0], s.head[1], s.head[2]};
        float[] hq = new float[]{s.head[3], s.head[4], s.head[5], s.head[6]};
        normalizeQuat(hq);
        if (!trackingBaseValid) {
            System.arraycopy(hp, 0, baseHeadPos, 0, 3);
            System.arraycopy(hq, 0, baseHeadRot, 0, 4);
            trackingBaseValid = true;
            applyCamera(cameraAnchorPos, cameraAnchorRot);
            return;
        }

        float[] invBase = inverse(baseHeadRot);
        float[] relativeRot = multiply(invBase, hq);
        float[] worldRot = multiply(cameraAnchorRot, relativeRot);
        normalizeQuat(worldRot);

        float[] arWorldDelta = new float[]{
                hp[0] - baseHeadPos[0],
                hp[1] - baseHeadPos[1],
                hp[2] - baseHeadPos[2]
        };
        float[] headLocalDelta = rotate(invBase, arWorldDelta);
        float[] roomDelta = rotate(cameraAnchorRot, headLocalDelta);
        float[] worldPos = new float[]{
                cameraAnchorPos[0] + roomDelta[0],
                cameraAnchorPos[1] + roomDelta[1],
                cameraAnchorPos[2] + roomDelta[2]
        };
        applyCamera(worldPos, worldRot);
    }

    private void applyCamera(float[] position, float[] rotation) {
        System.arraycopy(position, 0, lastCameraPos, 0, 3);
        System.arraycopy(rotation, 0, lastCameraRot, 0, 4);
        float[] forward = rotate(rotation, new float[]{0, 0, -1});
        float[] up = rotate(rotation, new float[]{0, 1, 0});
        camera.lookAt(
                position[0], position[1], position[2],
                position[0] + forward[0], position[1] + forward[1], position[2] + forward[2],
                up[0], up[1], up[2]);
    }

    private static float[] unityTrsToFilament(JSONObject item) {
        float[] p = jsonVec3(item.optJSONObject("position"), 0, 0, 0);
        float[] q = jsonQuat(item.optJSONObject("rotation"));
        float[] s = jsonVec3(item.optJSONObject("scale"), 1, 1, 1);
        // Reflection across Z converts Unity's left-handed scene transforms to
        // glTF/Filament right-handed space. Applied at every hierarchy level,
        // S*M*S composes correctly through the whole prefab tree.
        p[2] = -p[2];
        q[0] = -q[0];
        q[1] = -q[1];
        normalizeQuat(q);
        return trs(p, q, s);
    }

    private static float[] trs(float[] p, float[] q, float[] s) {
        float x = q[0], y = q[1], z = q[2], w = q[3];
        float xx = x * x, yy = y * y, zz = z * z;
        float xy = x * y, xz = x * z, yz = y * z;
        float wx = w * x, wy = w * y, wz = w * z;
        float m00 = 1f - 2f * (yy + zz);
        float m01 = 2f * (xy - wz);
        float m02 = 2f * (xz + wy);
        float m10 = 2f * (xy + wz);
        float m11 = 1f - 2f * (xx + zz);
        float m12 = 2f * (yz - wx);
        float m20 = 2f * (xz - wy);
        float m21 = 2f * (yz + wx);
        float m22 = 1f - 2f * (xx + yy);
        return new float[]{
                m00 * s[0], m10 * s[0], m20 * s[0], 0,
                m01 * s[1], m11 * s[1], m21 * s[1], 0,
                m02 * s[2], m12 * s[2], m22 * s[2], 0,
                p[0], p[1], p[2], 1
        };
    }

    private static float[] jsonVec3(JSONObject o, float dx, float dy, float dz) {
        if (o == null) return new float[]{dx, dy, dz};
        return new float[]{
                (float) o.optDouble("x", dx),
                (float) o.optDouble("y", dy),
                (float) o.optDouble("z", dz)
        };
    }

    private static float[] jsonQuat(JSONObject o) {
        if (o == null) return new float[]{0, 0, 0, 1};
        float[] q = new float[]{
                (float) o.optDouble("x", 0),
                (float) o.optDouble("y", 0),
                (float) o.optDouble("z", 0),
                (float) o.optDouble("w", 1)
        };
        normalizeQuat(q);
        return q;
    }

    private static float[] multiply(float[] a, float[] b) {
        return new float[]{
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
        };
    }

    private static float[] inverse(float[] q) {
        float d = q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3];
        if (d < 1e-8f) return new float[]{0, 0, 0, 1};
        return new float[]{-q[0] / d, -q[1] / d, -q[2] / d, q[3] / d};
    }

    private static float[] rotate(float[] q, float[] v) {
        float[] p = new float[]{v[0], v[1], v[2], 0};
        float[] out = multiply(multiply(q, p), inverse(q));
        return new float[]{out[0], out[1], out[2]};
    }

    private static void normalizeQuat(float[] q) {
        float d = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (d < 1e-8f) {
            q[0] = q[1] = q[2] = 0;
            q[3] = 1;
            return;
        }
        q[0] /= d;
        q[1] /= d;
        q[2] /= d;
        q[3] /= d;
    }

    private byte[] readBytesAsset(String path) throws Exception {
        try (InputStream in = assets.open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private String readTextAsset(String path) throws Exception {
        return new String(readBytesAsset(path), java.nio.charset.StandardCharsets.UTF_8);
    }

    public void destroyRenderer() {
        if (destroyed) return;
        destroyed = true;
        pauseRendering();
        try { uiHelper.detach(); } catch (Throwable ignored) {}
        try { displayHelper.detach(); } catch (Throwable ignored) {}
        if (swapChain != null) {
            engine.destroySwapChain(swapChain);
            swapChain = null;
        }
        for (FilamentAsset asset : loadedAssets) {
            try { scene.removeEntities(asset.getEntities()); } catch (Throwable ignored) {}
            try { assetLoader.destroyAsset(asset); } catch (Throwable ignored) {}
        }
        loadedAssets.clear();
        for (int entity : lodgeLightEntities) {
            try { scene.removeEntity(entity); } catch (Throwable ignored) {}
            try { engine.destroyEntity(entity); } catch (Throwable ignored) {}
            try { EntityManager.get().destroy(entity); } catch (Throwable ignored) {}
        }
        lodgeLightEntities.clear();
        if (crtMenuTexture != null) {
            try { engine.destroyTexture(crtMenuTexture); } catch (Throwable ignored) {}
            crtMenuTexture = null;
        }
        displayAsset = null;
        try { resourceLoader.destroy(); } catch (Throwable ignored) {}
        try { assetLoader.destroy(); } catch (Throwable ignored) {}
        try { materialProvider.destroyMaterials(); } catch (Throwable ignored) {}
        try { materialProvider.destroy(); } catch (Throwable ignored) {}
        try { scene.removeEntity(lightEntity); } catch (Throwable ignored) {}
        try { engine.destroyEntity(lightEntity); } catch (Throwable ignored) {}
        try { engine.destroySkybox(skybox); } catch (Throwable ignored) {}
        try { engine.destroyCameraComponent(cameraEntity); } catch (Throwable ignored) {}
        try { EntityManager.get().destroy(cameraEntity); } catch (Throwable ignored) {}
        try { EntityManager.get().destroy(lightEntity); } catch (Throwable ignored) {}
        try { engine.destroyView(view); } catch (Throwable ignored) {}
        try { engine.destroyScene(scene); } catch (Throwable ignored) {}
        try { engine.destroyRenderer(renderer); } catch (Throwable ignored) {}
        try { engine.flushAndWait(); } catch (Throwable ignored) {}
        try { engine.destroy(); } catch (Throwable ignored) {}
    }
}
