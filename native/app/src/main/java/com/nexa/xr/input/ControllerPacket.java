package com.nexa.xr.input;
import java.nio.charset.StandardCharsets;import java.util.Locale;
/** The supplied PhoneXR PH5 contract: 29 fields, loopback transport, synthetic arms. */
public final class ControllerPacket {
    public static final class State {
        public final int[] buttons=new int[2];public final float[] stickX=new float[2],stickY=new float[2],yaw=new float[2],pitch=new float[2];
    }
    private static float finite(float value,float range){return Float.isFinite(value)?Math.max(-range,Math.min(range,value)):0;}
    public static byte[] encode(State state,boolean active) {
        StringBuilder text=new StringBuilder("PH5");
        for(int hand=0;hand<2;hand++) {
            float yaw=active?finite(state.yaw[hand],(float)Math.PI):0,pitch=active?finite(state.pitch[hand],1.45f):0;
            float sy=(float)Math.sin(yaw/2),cy=(float)Math.cos(yaw/2),sp=(float)Math.sin(pitch/2),cp=(float)Math.cos(pitch/2);
            text.append(String.format(Locale.ROOT," %d 0 0 0 0.5 0.5 0 %.4f %.4f %.4f %.4f %d %.3f %.3f",active?1:0,cy*sp,sy*cp,-sy*sp,cy*cp,active?state.buttons[hand]&127:0,active?finite(state.stickX[hand],1):0,active?finite(state.stickY[hand],1):0));
        }
        text.append(" 0");byte[] bytes=text.toString().getBytes(StandardCharsets.US_ASCII);
        if(bytes.length>255)throw new IllegalStateException("Controller packet exceeds runtime buffer");return bytes;
    }
}
