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
old_default = "public VRProvider stereoProviderPluginID = VRProvider.OPENXR;"
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

switch_anchor = """            dh.vr = switch (dh.vrSettings.stereoProviderPluginID) {
                case OPENVR -> new MCOpenVR(instance, dh);
                case OPENXR -> new MCOpenXR(instance, dh);
                default -> new NullVR(instance, dh);
            };"""
switch_new = """            dh.vr = switch (dh.vrSettings.stereoProviderPluginID) {
                case OPENVR -> new MCOpenVR(instance, dh);
                case OPENXR -> new MCOpenXR(instance, dh);
                case NEXA -> new NexaVR(instance, dh);
                default -> new NullVR(instance, dh);
            };"""
if switch_anchor not in v:
    raise SystemExit("VRState provider switch anchor missing")
v = v.replace(switch_anchor, switch_new, 1)
vrstate.write_text(v, encoding="utf-8")

print("Nexa Vivecraft integration patch applied for OpenXR-1.20.4")
