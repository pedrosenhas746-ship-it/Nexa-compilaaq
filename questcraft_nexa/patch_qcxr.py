from pathlib import Path
import re
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_qcxr.py <qcxr-root>")

root = Path(sys.argv[1])

# The launcher must not try to initialize a Quest/Pico OpenXR runtime on a normal phone.
jni = root / "Assets/Scripts/JNIStorage.cs"
s = jni.read_text(encoding="utf-8-sig")
old_close = """    static void CloseXR()
    {
        XRGeneralSettings.Instance.Manager.activeLoader.Stop();
        XRGeneralSettings.Instance.Manager.activeLoader.Deinitialize();
    }"""
new_close = """    static void CloseXR()
    {
        var settings = XRGeneralSettings.Instance;
        if (settings == null || settings.Manager == null || settings.Manager.activeLoader == null) return;
        settings.Manager.activeLoader.Stop();
        settings.Manager.activeLoader.Deinitialize();
    }"""
if old_close not in s:
    raise SystemExit("JNIStorage CloseXR anchor missing")
s = s.replace(old_close, new_close, 1)
old_model = 'apiClass.SetStatic("model", OpenXRFeatureSystemInfo.GetHeadsetName());'
if old_model not in s:
    raise SystemExit("JNIStorage model anchor missing")
s = s.replace(old_model, 'apiClass.SetStatic("model", "NEXA_PHONE");', 1)
jni.write_text(s, encoding="utf-8")

manifest = root / "Assets/Plugins/Android/AndroidManifest.xml"
m = manifest.read_text(encoding="utf-8-sig")
# Remove explicit OpenXR permission/runtime discovery and Quest/Pico-only requirements.
m = re.sub(r'\s*<uses-permission android:name="org\.khronos\.openxr\.permission\.OPENXR_SYSTEM"\s*/>', '', m)
m = re.sub(r'\s*<uses-feature android:name="android\.hardware\.vr\.headtracking"[^>]*/>', '', m)
m = re.sub(r'\s*<queries>.*?</queries>', '', m, flags=re.S)
m = re.sub(r'\s*<meta-data android:name="pvr\.[^"]+"[^>]*/>', '', m)
m = re.sub(r'\s*<meta-data android:name="com\.oculus\.[^"]+"[^>]*/>', '', m)
m = re.sub(r'\s*<meta-data android:name="com\.oculus\.intent\.category\.VR"[^>]*/>', '', m)
m = m.replace('        <category android:name="com.oculus.intent.category.VR" />\n', '')
if 'android.permission.CAMERA' not in m:
    m = m.replace(
        '<uses-permission android:name="android.permission.RECORD_AUDIO" />',
        '<uses-permission android:name="android.permission.RECORD_AUDIO" />\n'
        '  <uses-permission android:name="android.permission.CAMERA" />\n'
        '  <uses-feature android:name="android.hardware.camera" android:required="true" />\n'
        '  <uses-feature android:name="android.hardware.camera.ar" android:required="false" />',
        1,
    )
manifest.write_text(m, encoding="utf-8")

# A local AAR does not bring its Maven transitive dependencies into Unity automatically.
gradle = root / "Assets/Plugins/Android/mainTemplate.gradle"
g = gradle.read_text(encoding="utf-8")
dep_anchor = '    implementation(name: "Pojlib-release", ext: "aar")\n'
if dep_anchor not in g:
    raise SystemExit("mainTemplate.gradle Pojlib anchor missing")
g = g.replace(
    dep_anchor,
    dep_anchor + '\n    implementation("com.google.ar:core:1.45.0")\n'
    '    implementation("com.google.mediapipe:tasks-vision:0.10.14")\n',
    1,
)
gradle.write_text(g, encoding="utf-8")

# Disable Android OpenXR loader. Minecraft/Vivecraft itself renders the two eyes.
xr = root / "Assets/XR/XRGeneralSettings.asset"
x = xr.read_text(encoding="utf-8")
android_providers = re.compile(
    r'(m_Name: Android Providers\n(?:.*\n)*?  m_Loaders:)\n(?:  - .*\n)+',
    re.M,
)
x, count_loaders = android_providers.subn(r'\1 []\n', x, count=1)
android_settings = re.compile(
    r'(m_Name: Android Settings\n(?:.*\n)*?  m_InitManagerOnStart:) 1',
    re.M,
)
x, count_init = android_settings.subn(r'\1 0', x, count=1)
if count_loaders != 1 or count_init != 1:
    raise SystemExit(f"XR settings patch failed loaders={count_loaders} init={count_init}")
xr.write_text(x, encoding="utf-8")

print("QCXR patched for Nexa phone runtime")
