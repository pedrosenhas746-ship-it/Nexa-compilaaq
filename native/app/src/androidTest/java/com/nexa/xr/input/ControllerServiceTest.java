package com.nexa.xr.input;
import android.app.Activity;import android.content.*;import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;import com.nexa.xr.HomeActivity;
import java.net.*;import java.nio.charset.StandardCharsets;import org.junit.Test;import static org.junit.Assert.*;

public class ControllerServiceTest {
    @Test public void foregroundOverlaySendsActiveAndFinalNeutralPackets()throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        Activity activity=instrumentation.startActivitySync(new Intent(context,HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrumentation.waitForIdleSync();
        Intent service=new Intent(context,ControllerOverlayService.class);
        try(DatagramSocket receiver=new DatagramSocket(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),42424))) {
            receiver.setSoTimeout(5000);instrumentation.runOnMainSync(()->context.startForegroundService(service));
            String[] active=receive(receiver);assertEquals(30,active.length);assertEquals("PH5",active[0]);assertEquals("1",active[1]);assertEquals("1",active[15]);
            assertTrue(ControllerOverlayService.running);instrumentation.runOnMainSync(()->context.stopService(service));
            boolean neutral=false;long deadline=SystemClock.uptimeMillis()+5000;
            while(SystemClock.uptimeMillis()<deadline){String[] packet=receive(receiver);if(packet[1].equals("0")&&packet[15].equals("0")){assertEquals("0",packet[12]);assertEquals("0",packet[26]);assertEquals("0.000",packet[13]);assertEquals("0.000",packet[28]);neutral=true;break;}}
            assertTrue("final release packet missing",neutral);assertFalse(ControllerOverlayService.running);
        }finally{context.stopService(service);instrumentation.runOnMainSync(activity::finish);}
    }
    private static String[] receive(DatagramSocket socket)throws Exception {byte[] bytes=new byte[256];DatagramPacket packet=new DatagramPacket(bytes,bytes.length);socket.receive(packet);assertTrue(packet.getLength()<=255);assertTrue(packet.getAddress().isLoopbackAddress());return new String(bytes,0,packet.getLength(),StandardCharsets.US_ASCII).split(" ");}
}
