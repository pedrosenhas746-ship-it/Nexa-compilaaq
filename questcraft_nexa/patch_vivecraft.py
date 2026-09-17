from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_vivecraft.py <vivecraft-root>")

root = Path(sys.argv[1])

settings = root / "common/src/main/java/org/vivecraft/client_vr/settings/VRSettings.java"
s = settings.read_text(encoding="utf-8")
old = """    public enum VRProvider implements OptionEnum<VRProvider> {
        OPENVR,
        OPENXR,
        NULLVR
    }"""
new = """    public enum VRProvider implements OptionEnum<VRProvider> {
        OPENVR,
        OPENXR,
        NULLVR,
        NEXA
    }"""
if old not in s:
    raise SystemExit("VRSettings provider enum anchor missing")
s = s.replace(old, new, 1)
old_default = "public VRProvider stereoProviderPluginID = VRProvider.OPENVR;"
if old_default not in s:
    raise SystemExit("VRSettings default provider anchor missing")
s = s.replace(old_default, "public VRProvider stereoProviderPluginID = VRProvider.NEXA;", 1)
settings.write_text(s, encoding="utf-8")

vrstate = root / "common/src/main/java/org/vivecraft/client_vr/VRState.java"
v = vrstate.read_text(encoding="utf-8")
import_anchor = "import org.vivecraft.client_vr.provider.nullvr.NullVR;"
if import_anchor not in v:
    raise SystemExit("VRState import anchor missing")
v = v.replace(import_anchor, import_anchor + "\nimport org.vivecraft.client_vr.provider.nexavr.NexaVR;", 1)
switch_anchor = "                case OPENXR -> dh.vr = new MCOpenXR(instance, dh);\n                default -> dh.vr = new NullVR(instance, dh);"
if switch_anchor not in v:
    raise SystemExit("VRState switch anchor missing")
v = v.replace(
    switch_anchor,
    "                case OPENXR -> dh.vr = new MCOpenXR(instance, dh);\n"
    "                case NEXA -> dh.vr = new NexaVR(instance, dh);\n"
    "                default -> dh.vr = new NullVR(instance, dh);",
    1,
)
vrstate.write_text(v, encoding="utf-8")

mixin = root / "common/src/main/java/org/vivecraft/mixin/client_vr/MinecraftVRMixin.java"
m = mixin.read_text(encoding="utf-8")
import_anchor = "import org.vivecraft.client_vr.provider.control.VRInputAction;"
if import_anchor not in m:
    raise SystemExit("MinecraftVRMixin import anchor missing")
m = m.replace(import_anchor, import_anchor + "\nimport org.vivecraft.client_vr.provider.nexavr.NexaLensDistortion;", 1)
mirror_anchor = """            int screenWidth = ((WindowExtension) (Object) this.window).vivecraft$getActualScreenWidth() / 2;
            int screenHeight = ((WindowExtension) (Object) this.window).vivecraft$getActualScreenHeight();
            if (rendertarget != null) {"""
mirror_new = """            int screenWidth = ((WindowExtension) (Object) this.window).vivecraft$getActualScreenWidth() / 2;
            int screenHeight = ((WindowExtension) (Object) this.window).vivecraft$getActualScreenHeight();
            if (NexaLensDistortion.isEnabledFor(ClientDataHolderVR.getInstance().vr) &&
                NexaLensDistortion.blitDual(rendertarget, rendertarget1, screenWidth * 2, screenHeight)) {
                return;
            }
            if (rendertarget != null) {"""
if mirror_anchor not in m:
    raise SystemExit("MinecraftVRMixin DUAL mirror anchor missing")
m = m.replace(mirror_anchor, mirror_new, 1)
mixin.write_text(m, encoding="utf-8")

print("Nexa Vivecraft integration patch applied")
