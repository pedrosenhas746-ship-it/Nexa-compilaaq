package com.nexa.xr;

public final class QuestAppProfile {
    public final String packageName;
    public final String label;
    public final String engine;
    public final boolean arm64;
    public final boolean arm32;
    public final boolean x86_64;
    public final boolean openXrLoader;
    public final boolean ovrPlugin;
    public final boolean vrApi;
    public final boolean ovrPlatform;
    public final boolean launchable;
    public final String mode;
    public final String notes;

    QuestAppProfile(
            String packageName,
            String label,
            String engine,
            boolean arm64,
            boolean arm32,
            boolean x86_64,
            boolean openXrLoader,
            boolean ovrPlugin,
            boolean vrApi,
            boolean ovrPlatform,
            boolean launchable,
            String mode,
            String notes) {
        this.packageName = packageName;
        this.label = label;
        this.engine = engine;
        this.arm64 = arm64;
        this.arm32 = arm32;
        this.x86_64 = x86_64;
        this.openXrLoader = openXrLoader;
        this.ovrPlugin = ovrPlugin;
        this.vrApi = vrApi;
        this.ovrPlatform = ovrPlatform;
        this.launchable = launchable;
        this.mode = mode;
        this.notes = notes;
    }

    public boolean isVrCandidate() {
        return openXrLoader || ovrPlugin || vrApi || "UNITY".equals(engine) || "UNREAL".equals(engine);
    }

    public String shortSummary() {
        StringBuilder s = new StringBuilder();
        s.append(engine).append(" • ").append(mode);
        if (openXrLoader) s.append(" • OpenXR");
        if (ovrPlugin) s.append(" • OVRPlugin");
        if (vrApi) s.append(" • VrApi");
        return s.toString();
    }
}

