package com.nexa.xr;

import android.app.Service;
import android.content.*;
import android.os.*;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Android Binder callbacks and own-process log access; no PhoneXR/game emulation. */
public final class RuntimeConnectionTest {
    public static final class BinderService extends Service {
        @Override public IBinder onBind(Intent intent){return new Binder();}
    }
    public static final class NullService extends Service {
        @Override public IBinder onBind(Intent intent){return null;}
    }
    private Context target(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private ComponentName mock(Class<?> type){return new ComponentName(InstrumentationRegistry.getInstrumentation().getContext().getPackageName(),type.getName());}
    @Test public void realBinderConnectionIsReportedWithoutClaimingVr(){
        String result=RuntimeConnectionCheck.bindCheck(target(),mock(BinderService.class),2500);
        assertTrue(result,result.contains("Vincular serviço: Binder conectado"));
        assertTrue(result,result.contains("não confirma funcionamento de VR"));
    }
    @Test public void nullBindingDoesNotLookLikeSuccessfulRuntime(){
        String result=RuntimeConnectionCheck.bindCheck(target(),mock(NullService.class),2500);
        assertTrue(result,result.contains("Serviço retornou Binder nulo"));
        assertFalse(result,result.contains("Vincular serviço: Binder conectado"));
    }
    @Test public void missingRuntimeClassifiesPackageFailure(){
        String result=RuntimeConnectionCheck.packageCheck(target(),"com.nexa.missing.runtime.test");
        assertTrue(result,result.contains("NameNotFoundException"));
        assertFalse(result,result.contains("Pacote visível: sim"));
    }
    @Test public void logCollectionIncludesOwnProcessFailureWithoutReadLogsPermission()throws Exception {
        String marker="NEXA-IPC-TEST-"+System.nanoTime();Log.e("NEXA-TEST",marker);Thread.sleep(200);
        String result=OwnProcessLogs.collect();assertTrue(result,result.contains(marker));
        assertTrue(result,result.contains("PID "+android.os.Process.myPid()));
        assertTrue(result.length()<26000);
    }
}
