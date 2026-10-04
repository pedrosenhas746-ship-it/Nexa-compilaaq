import com.nexa.xr.input.ControllerPacket;import java.util.*;
public class ControllerPacketTest {
    public static void main(String[] args){ControllerPacket.State s=new ControllerPacket.State();s.buttons[0]=1|4|16;s.buttons[1]=2|8;s.stickX[0]=2;s.stickY[0]=Float.NaN;s.yaw[1]=1.2f;s.pitch[1]=-.5f;
        byte[] packet=ControllerPacket.encode(s,true);String[] f=new String(packet,java.nio.charset.StandardCharsets.US_ASCII).split(" ");
        if(f.length!=30||packet.length>255||!f[0].equals("PH5"))throw new AssertionError("format");
        if(!f[12].equals("21")||!f[26].equals("10")||!f[13].equals("1.000")||!f[14].equals("0.000")||!f[29].equals("0"))throw new AssertionError(Arrays.toString(f));
        double length=0;for(int i=22;i<=25;i++)length+=Math.pow(Double.parseDouble(f[i]),2);if(Math.abs(length-1)>.001)throw new AssertionError("bad quaternion");
        f=new String(ControllerPacket.encode(s,false),java.nio.charset.StandardCharsets.US_ASCII).split(" ");for(int index:new int[]{1,12,13,14,15,26,27,28,29})if(Double.parseDouble(f[index])!=0)throw new AssertionError("neutral packet retained input");
        System.out.println("Controller protocol: 29 fields, bounded axes, normalized quaternions and release verified.");
    }
}
