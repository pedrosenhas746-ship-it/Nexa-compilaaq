package org.freedesktop.monado.auxiliary;
import android.app.Activity;
import android.view.SurfaceHolder;
/** Compile-time ABI declaration. Never included in the repaired PhoneXR APK. */
public class MonadoView {
    public static MonadoView attachToActivity(Activity activity) { throw new UnsupportedOperationException("ABI declaration only"); }
    public SurfaceHolder waitGetSurfaceHolder(int millis) { throw new UnsupportedOperationException("ABI declaration only"); }
}
