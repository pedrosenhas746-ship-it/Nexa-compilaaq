package com.nexa.questcraft.lodge;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Source-exact metadata extracted from QuestCraft CardboardCraft Main.unity. */
public final class NexaRoomSpec {
    public static final String ASSET_PATH = "nexa/nexa-room.json";
    public static final String EXPECTED_FORMAT = "nexa-questcraft-room-v1";
    public static final String EXPECTED_SOURCE_COMMIT = "a8d46ea0db48d31015c1794bf5159a7b9c6edb4d";

    private final JsonObject root;

    private NexaRoomSpec(JsonObject root) {
        this.root = root;
    }

    public static NexaRoomSpec load(Context context) throws Exception {
        try (InputStreamReader reader = new InputStreamReader(
                context.getAssets().open(ASSET_PATH), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!EXPECTED_FORMAT.equals(root.get("format").getAsString())) {
                throw new IllegalStateException("Unsupported Nexa room spec format");
            }
            JsonObject source = root.getAsJsonObject("source");
            if (source == null || !EXPECTED_SOURCE_COMMIT.equals(source.get("commit").getAsString())) {
                throw new IllegalStateException("QuestCraft room source commit mismatch");
            }
            NexaRoomSpec spec = new NexaRoomSpec(root);
            spec.verifyAnchors();
            return spec;
        }
    }

    private void verifyAnchors() {
        JsonObject qc = root.getAsJsonObject("qcworld");
        if (qc == null || qc.getAsJsonObject("source") == null ||
                !"Assets/QCWorld/QCWorld.obj".equals(qc.getAsJsonObject("source").get("path").getAsString())) {
            throw new IllegalStateException("QCWorld source missing");
        }
        JsonObject p = qc.getAsJsonObject("position");
        if (p == null || Math.abs(p.get("x").getAsFloat() + 18.58f) > 0.0001f ||
                Math.abs(p.get("y").getAsFloat() + 125.09f) > 0.0001f ||
                Math.abs(p.get("z").getAsFloat() - 1.0f) > 0.0001f) {
            throw new IllegalStateException("QCWorld transform mismatch");
        }
        boolean play = false;
        for (var e : root.getAsJsonArray("main_menu")) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("name") && "Play Button".equals(o.get("name").getAsString())) {
                play = true;
                break;
            }
        }
        if (!play) throw new IllegalStateException("Original Play Button missing");
    }

    public JsonObject qcWorld() { return root.getAsJsonObject("qcworld"); }
    public JsonArray props() { return root.getAsJsonArray("props"); }
    public JsonArray menuObjects() { return root.getAsJsonArray("main_menu"); }
    public JsonArray xrOriginObjects() { return root.getAsJsonArray("xr_origin"); }
    public JsonArray materials() { return root.getAsJsonArray("qcworld_materials"); }
    public JsonObject raw() { return root; }

    public int menuObjectCount() { return menuObjects().size(); }
    public int materialCount() { return materials().size(); }
    public int propCount() { return props().size(); }
}
