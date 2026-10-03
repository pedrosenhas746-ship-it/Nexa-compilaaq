package com.nexa.xr;
import android.app.Activity;
import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

public final class StartupTest {
    private void opens(Class<? extends Activity> type)throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        Activity activity=instrumentation.startActivitySync(new Intent(context,type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        // Let asynchronous package scanning/sensor callbacks run; failures kill instrumentation.
        Thread.sleep(2500);
        assertFalse(activity.isFinishing());assertFalse(activity.isDestroyed());
        assertTrue(activity.getWindow().getDecorView().isAttachedToWindow());
        instrumentation.runOnMainSync(activity::finish);
    }
    @Test public void phoneMenuOpensWithoutSensorOrScanStartup()throws Exception{opens(HomeActivity.class);}
    @Test public void vrMenuAndBackgroundScanStayOpen()throws Exception{opens(MainActivity.class);}
    @Test public void importScreenOpens()throws Exception{opens(com.nexa.xr.importer.ImportActivity.class);}
}
