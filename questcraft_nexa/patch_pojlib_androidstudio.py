from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit('usage: patch_pojlib_androidstudio.py <pojlib-root>')

root = Path(sys.argv[1])

activity = r'''package pojlib;

import static android.os.Build.VERSION.SDK_INT;
import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;
import static org.lwjgl.glfw.CallbackBridge.sendMouseButton;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityGroup;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.DisplayMetrics;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

import fr.spse.gamepad_remapper.RemapperManager;
import fr.spse.gamepad_remapper.RemapperView;
import pojlib.input.AWTInputBridge;
import pojlib.input.EfficientAndroidLWJGLKeycode;
import pojlib.input.GrabListener;
import pojlib.input.LwjglGlfwKeycode;
import pojlib.input.gamepad.DefaultDataProvider;
import pojlib.input.gamepad.Gamepad;
import pojlib.util.Constants;
import pojlib.util.FileUtil;
import pojlib.util.Logger;

/**
 * Kept under the historical name because native Pojlib JNI looks this class up.
 * There is NO UnityPlayer here: this is a normal Android Activity hosting the
 * Minecraft EGL surface directly.
 */
public class UnityPlayerActivity extends ActivityGroup implements GrabListener {
    public static volatile ClipboardManager GLOBAL_CLIPBOARD;
    public static DisplayMetrics currentDisplayMetrics;

    protected FrameLayout rootLayout;
    protected SurfaceView minecraftSurface;
    protected NexaTrackingSurface nexaTracking;

    private Gamepad mGamepad;
    private RemapperManager mInputManager;

    static {
        System.loadLibrary("pojavexec");
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        Constants.initConstants(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        rootLayout = new FrameLayout(this);
        minecraftSurface = new SurfaceView(this);
        rootLayout.addView(minecraftSurface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Tiny independent GL surface: rear camera + ARCore + MediaPipe tracking.
        nexaTracking = new NexaTrackingSurface(this);
        FrameLayout.LayoutParams trackingLp = new FrameLayout.LayoutParams(4, 4);
        trackingLp.leftMargin = 0;
        trackingLp.topMargin = 0;
        rootLayout.addView(nexaTracking, trackingLp);
        setContentView(rootLayout);

        minecraftSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                nativeSetSurface(holder.getSurface(), Math.max(1, minecraftSurface.getWidth()), Math.max(1, minecraftSurface.getHeight()));
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                nativeSetSurface(holder.getSurface(), Math.max(1, width), Math.max(1, height));
                CallbackBridge.sendUpdateWindowSize(Math.max(1, width), Math.max(1, height));
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                nativeClearSurface();
            }
        });

        updateWindowSize(this);
        GLOBAL_CLIPBOARD = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        mInputManager = new RemapperManager(this, new RemapperView.Builder(null)
                .remapA(true).remapB(true).remapX(true).remapY(true)
                .remapLeftJoystick(true).remapRightJoystick(true)
                .remapStart(true).remapSelect(true)
                .remapLeftShoulder(true).remapRightShoulder(true)
                .remapLeftTrigger(true).remapRightTrigger(true)
                .remapDpad(true));
        CallbackBridge.nativeSetUseInputStackQueue(true);
    }

    public FrameLayout getRootLayout() { return rootLayout; }
    public SurfaceView getMinecraftSurface() { return minecraftSurface; }

    public static native void nativeSetSurface(Surface surface, int width, int height);
    public static native void nativeClearSurface();

    public static String installLWJGL(Activity activity) throws IOException {
        Logger.getInstance().appendToLog("Checking LWJGL");
        File lwjgl = new File(Constants.USER_HOME + "/lwjgl3/lwjgl-glfw-classes.jar");
        byte[] lwjglAsset = FileUtil.loadFromAssetToByte(activity, "lwjgl/lwjgl-glfw-classes.jar");
        if (!lwjgl.exists() || !FileUtil.matchingAssetFile(lwjgl, lwjglAsset)) {
            Objects.requireNonNull(lwjgl.getParentFile()).mkdirs();
            FileUtil.write(lwjgl.getAbsolutePath(), lwjglAsset);
        }
        return lwjgl.getAbsolutePath();
    }

    public void reinitUnity() {
        runOnUiThread(() -> {
            Intent start = getPackageManager().getLaunchIntentForPackage(getApplicationInfo().packageName);
            if (start != null) {
                start.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(start);
            }
            finish();
            Process.killProcess(Process.myPid());
        });
    }

    public static DisplayMetrics getDisplayMetrics(Activity activity) {
        DisplayMetrics dm = new DisplayMetrics();
        if (SDK_INT >= Build.VERSION_CODES.R) activity.getDisplay().getRealMetrics(dm);
        else activity.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        currentDisplayMetrics = dm;
        return dm;
    }

    public static void updateWindowSize(Activity activity) {
        currentDisplayMetrics = getDisplayMetrics(activity);
        CallbackBridge.physicalWidth = currentDisplayMetrics.widthPixels;
        CallbackBridge.physicalHeight = currentDisplayMetrics.heightPixels;
        CallbackBridge.windowWidth = currentDisplayMetrics.widthPixels;
        CallbackBridge.windowHeight = currentDisplayMetrics.heightPixels;
    }

    public static float dpToPx(float dp) { return dp * currentDisplayMetrics.density; }
    public static float pxToDp(float px) { return px / currentDisplayMetrics.density; }

    public static void querySystemClipboard() {
        ClipData data = GLOBAL_CLIPBOARD == null ? null : GLOBAL_CLIPBOARD.getPrimaryClip();
        if (data == null || data.getItemCount() == 0 || data.getItemAt(0).getText() == null) {
            AWTInputBridge.nativeClipboardReceived(null, null);
            return;
        }
        AWTInputBridge.nativeClipboardReceived(data.getItemAt(0).getText().toString(), "plain");
    }

    public static void putClipboardData(String data, String mimeType) {
        if (GLOBAL_CLIPBOARD == null) return;
        ClipData clip = "text/html".equals(mimeType)
                ? ClipData.newHtmlText("AWT Paste", data, data)
                : ClipData.newPlainText("AWT Paste", data);
        GLOBAL_CLIPBOARD.setPrimaryClip(clip);
    }

    private void createGamepad(InputDevice device) {
        mGamepad = new Gamepad(device, DefaultDataProvider.INSTANCE);
    }

    @SuppressLint("NewApi")
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        NexaXRBridge.onMotionEvent(event);
        if (Gamepad.isGamepadEvent(event)) {
            if (mGamepad == null) createGamepad(event.getDevice());
            mInputManager.handleMotionEventInput(this, event, mGamepad);
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        NexaXRBridge.onKeyEvent(event);
        if (processKeyEvent(event)) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ESCAPE);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    public boolean processKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_UNKNOWN || event.getRepeatCount() != 0) return true;
        if (key == KeyEvent.KEYCODE_VOLUME_DOWN || key == KeyEvent.KEYCODE_VOLUME_UP) return false;
        if (Gamepad.isGamepadEvent(event)) {
            if (mGamepad == null) createGamepad(event.getDevice());
            mInputManager.handleKeyEventInput(this, event, mGamepad);
            return true;
        }
        int index = EfficientAndroidLWJGLKeycode.getIndexByKey(key);
        if (EfficientAndroidLWJGLKeycode.containsIndex(index)) {
            EfficientAndroidLWJGLKeycode.execKey(event, index);
            return true;
        }
        return false;
    }

    public static boolean sendMouseButtonUnconverted(int button, boolean status) {
        int glfw = -1;
        if (button == MotionEvent.BUTTON_PRIMARY) glfw = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
        else if (button == MotionEvent.BUTTON_SECONDARY) glfw = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT;
        else if (button == MotionEvent.BUTTON_TERTIARY) glfw = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE;
        if (glfw < 0) return false;
        sendMouseButton(glfw, status);
        return true;
    }

    @Override public void onGrabState(boolean grabbing) { }

    @Override protected void onResume() {
        super.onResume();
        if (nexaTracking != null) nexaTracking.onResume();
    }

    @Override protected void onPause() {
        if (nexaTracking != null) nexaTracking.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (nexaTracking != null) nexaTracking.shutdown();
        nativeClearSurface();
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NexaTrackingSurface.CAMERA_REQUEST && nexaTracking != null) nexaTracking.onResume();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateWindowSize(this);
    }
}
'''
(root / 'src/main/java/pojlib/UnityPlayerActivity.java').write_text(activity, encoding='utf-8')

# Patch Pojlib dependencies: remove Unity stub, add tracking dependencies.
gradle = root / 'build.gradle'
g = gradle.read_text(encoding='utf-8')
g = g.replace('    implementation("blank:unity-classes")\n', '')
if 'com.google.ar:core' not in g:
    anchor = 'dependencies {\n    implementation("org.jetbrains:annotations:24.0.1")'
    g = g.replace(anchor,
        'dependencies {\n    implementation("com.google.ar:core:1.45.0")\n'
        '    implementation("com.google.mediapipe:tasks-vision:0.10.14")\n'
        '    implementation("org.jetbrains:annotations:24.0.1")', 1)
gradle.write_text(g, encoding='utf-8')

manifest = root / 'src/main/AndroidManifest.xml'
m = manifest.read_text(encoding='utf-8')
if 'android.permission.CAMERA' not in m:
    m = m.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    package="pojlib.android">',
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    package="pojlib.android">\n'
        '    <uses-permission android:name="android.permission.CAMERA" />\n'
        '    <uses-permission android:name="android.permission.INTERNET" />\n'
        '    <uses-feature android:name="android.hardware.camera" android:required="true" />\n'
        '    <uses-feature android:name="android.hardware.camera.ar" android:required="false" />')
manifest.write_text(m, encoding='utf-8')

# Nexa provider + dual-eye config.
instances = root / 'src/main/java/pojlib/util/json/MinecraftInstances.java'
i = instances.read_text(encoding='utf-8')
i = i.replace('obj.addProperty("stereoProviderPluginID", "OPENXR");',
              'obj.addProperty("stereoProviderPluginID", "NEXA");\n            obj.addProperty("displayMirrorMode", "DUAL");')
instances.write_text(i, encoding='utf-8')

# Install embedded Nexa Vivecraft immediately before launch checks.
api = root / 'src/main/java/pojlib/API.java'
a = api.read_text(encoding='utf-8')
needle = '        instance.updateMods(instances);\n'
if 'NexaEmbeddedMods.install(activity, instance);' not in a:
    a = a.replace(needle, needle + '        NexaEmbeddedMods.install(activity, instance);\n', 1)
api.write_text(a, encoding='utf-8')

# Direct Android window EGL instead of Unity-owned offscreen-only PBuffer.
egl = root / 'src/main/jni/egl_bridge.c'
e = egl.read_text(encoding='utf-8')
e = e.replace(
    'typedef EGLSurface eglCreatePbufferSurface_t (EGLDisplay dpy, EGLConfig config, const EGLint *attrib_list);',
    'typedef EGLSurface eglCreatePbufferSurface_t (EGLDisplay dpy, EGLConfig config, const EGLint *attrib_list);\n'
    'typedef EGLSurface eglCreateWindowSurface_t (EGLDisplay dpy, EGLConfig config, EGLNativeWindowType win, const EGLint *attrib_list);\n'
    'typedef EGLBoolean eglDestroySurface_t (EGLDisplay dpy, EGLSurface surface);')
e = e.replace('eglCreatePbufferSurface_t* eglCreatePbufferSurface_p;',
              'eglCreatePbufferSurface_t* eglCreatePbufferSurface_p;\neglCreateWindowSurface_t* eglCreateWindowSurface_p;\neglDestroySurface_t* eglDestroySurface_p;')
e = e.replace('EGLConfig xrConfig;\n', 'EGLConfig xrConfig;\nANativeWindow* androidWindow = NULL;\n')
e = e.replace('    eglCreatePbufferSurface_p = (eglCreatePbufferSurface_t*) eglGetProcAddress_p("eglCreatePbufferSurface");',
              '    eglCreatePbufferSurface_p = (eglCreatePbufferSurface_t*) eglGetProcAddress_p("eglCreatePbufferSurface");\n'
              '    eglCreateWindowSurface_p = (eglCreateWindowSurface_t*) eglGetProcAddress_p("eglCreateWindowSurface");\n'
              '    eglDestroySurface_p = (eglDestroySurface_t*) eglGetProcAddress_p("eglDestroySurface");')
e = e.replace('            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,',
              '            EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,')
e = e.replace(
    '    xrEglSurface = eglCreatePbufferSurface_p(xrEglDisplay, xrConfig,\n                                           NULL);',
    '    if (androidWindow != NULL && eglCreateWindowSurface_p != NULL) {\n'
    '        xrEglSurface = eglCreateWindowSurface_p(xrEglDisplay, xrConfig, androidWindow, NULL);\n'
    '    } else {\n'
    '        xrEglSurface = eglCreatePbufferSurface_p(xrEglDisplay, xrConfig, NULL);\n'
    '    }')
insert = r'''
JNIEXPORT void JNICALL
Java_pojlib_UnityPlayerActivity_nativeSetSurface(JNIEnv *env, jclass clazz, jobject surface, jint width, jint height) {
    if (androidWindow != NULL) {
        ANativeWindow_release(androidWindow);
        androidWindow = NULL;
    }
    if (surface != NULL) androidWindow = ANativeWindow_fromSurface(env, surface);
    savedWidth = width > 0 ? width : 1;
    savedHeight = height > 0 ? height : 1;
}

JNIEXPORT void JNICALL
Java_pojlib_UnityPlayerActivity_nativeClearSurface(JNIEnv *env, jclass clazz) {
    if (androidWindow != NULL) {
        ANativeWindow_release(androidWindow);
        androidWindow = NULL;
    }
}

'''
marker = 'JNIEXPORT JNICALL jlong\nJava_pojlib_util_JREUtils_getEGLDisplayPtr'
if insert.strip() not in e:
    e = e.replace(marker, insert + marker)
egl.write_text(e, encoding='utf-8')

print('Pojlib patched for native Android Studio / Nexa runtime')
