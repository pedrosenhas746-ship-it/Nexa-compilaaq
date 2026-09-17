from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_pojlib.py <pojlib-root>")

root = Path(sys.argv[1])
activity = root / "src/main/java/pojlib/UnityPlayerActivity.java"
s = activity.read_text(encoding="utf-8")

s = s.replace(
    "    private Gamepad mGamepad = null;\n",
    "    private Gamepad mGamepad = null;\n    private NexaTrackingSurface mNexaTracking;\n",
    1,
)
s = s.replace(
    "        mUnityPlayer.requestFocus();\n\n        updateWindowSize(this);",
    "        mUnityPlayer.requestFocus();\n\n        mNexaTracking = new NexaTrackingSurface(this);\n"
    "        addContentView(mNexaTracking, new android.widget.FrameLayout.LayoutParams(2, 2));\n\n"
    "        updateWindowSize(this);",
    1,
)
s = s.replace(
    "    public boolean dispatchGenericMotionEvent(MotionEvent event) {\n",
    "    public boolean dispatchGenericMotionEvent(MotionEvent event) {\n        NexaXRBridge.onMotionEvent(event);\n",
    1,
)
s = s.replace(
    "    public boolean dispatchKeyEvent(KeyEvent event) {\n",
    "    public boolean dispatchKeyEvent(KeyEvent event) {\n        NexaXRBridge.onKeyEvent(event);\n",
    1,
)
s = s.replace(
    "    @Override protected void onDestroy ()\n    {\n        mUnityPlayer.destroy();",
    "    @Override protected void onDestroy ()\n    {\n        if (mNexaTracking != null) mNexaTracking.shutdown();\n        mUnityPlayer.destroy();",
    1,
)
s = s.replace(
    "    @Override protected void onPause()\n    {\n        super.onPause();\n\n        mUnityPlayer.pause();",
    "    @Override protected void onPause()\n    {\n        if (mNexaTracking != null) mNexaTracking.onPause();\n"
    "        super.onPause();\n\n        mUnityPlayer.pause();",
    1,
)
s = s.replace(
    "    @Override protected void onResume()\n    {\n        super.onResume();\n\n        mUnityPlayer.resume();\n    }",
    "    @Override protected void onResume()\n    {\n        super.onResume();\n\n        mUnityPlayer.resume();\n"
    "        if (mNexaTracking != null) mNexaTracking.onResume();\n    }\n\n"
    "    @Override\n    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {\n"
    "        super.onRequestPermissionsResult(requestCode, permissions, grantResults);\n"
    "        if (requestCode == NexaTrackingSurface.CAMERA_REQUEST && mNexaTracking != null) {\n"
    "            mNexaTracking.onResume();\n        }\n    }",
    1,
)
required = [
    "NexaXRBridge.onMotionEvent(event);",
    "NexaXRBridge.onKeyEvent(event);",
    "new NexaTrackingSurface(this)",
    "mNexaTracking.shutdown()",
]
missing = [x for x in required if x not in s]
if missing:
    raise SystemExit("UnityPlayerActivity patch failed: " + ", ".join(missing))
activity.write_text(s, encoding="utf-8")

gradle = root / "build.gradle"
g = gradle.read_text(encoding="utf-8")
anchor = 'dependencies {\n    implementation("org.jetbrains:annotations:24.0.1")'
if anchor not in g:
    raise SystemExit("build.gradle dependency anchor missing")
g = g.replace(
    anchor,
    'dependencies {\n    implementation("com.google.ar:core:1.45.0")\n'
    '    implementation("com.google.mediapipe:tasks-vision:0.10.14")\n'
    '    implementation("org.jetbrains:annotations:24.0.1")',
    1,
)
gradle.write_text(g, encoding="utf-8")

manifest = root / "src/main/AndroidManifest.xml"
m = manifest.read_text(encoding="utf-8")
manifest_anchor = '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    package="pojlib.android">'
if manifest_anchor not in m:
    raise SystemExit("manifest anchor missing")
m = m.replace(
    manifest_anchor,
    manifest_anchor + '\n    <uses-permission android:name="android.permission.CAMERA" />\n'
    '    <uses-feature android:name="android.hardware.camera" android:required="true" />\n'
    '    <uses-feature android:name="android.hardware.camera.ar" android:required="false" />',
    1,
)
manifest.write_text(m, encoding="utf-8")

instances = root / "src/main/java/pojlib/util/json/MinecraftInstances.java"
i = instances.read_text(encoding="utf-8")
if 'obj.addProperty("stereoProviderPluginID", "OPENXR");' not in i:
    raise SystemExit("Vivecraft provider config anchor missing")
i = i.replace(
    'obj.addProperty("stereoProviderPluginID", "OPENXR");',
    'obj.addProperty("stereoProviderPluginID", "NEXA");',
    1,
)
instances.write_text(i, encoding="utf-8")

api = root / "src/main/java/pojlib/API.java"
a = api.read_text(encoding="utf-8")
anchor = "        instance.updateMods(instances);\n"
if anchor not in a:
    raise SystemExit("API.prelaunch anchor missing")
a = a.replace(anchor, anchor + "        NexaEmbeddedMods.install(activity, instance);\n", 1)
api.write_text(a, encoding="utf-8")

print("Nexa Pojlib integration patch applied")
