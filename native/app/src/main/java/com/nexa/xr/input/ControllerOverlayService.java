package com.nexa.xr.input;
import android.app.*;import android.content.*;import android.content.pm.ServiceInfo;import android.graphics.*;import android.os.*;import android.provider.Settings;import android.view.*;import android.widget.*;
import com.nexa.xr.HomeActivity;import com.nexa.xr.RuntimeDiagnostics;import java.net.*;import java.util.*;import java.util.concurrent.*;

/** Explicitly started touch controller. Does not claim optical hand tracking or inject head pose. */
public final class ControllerOverlayService extends Service {
    public static final String STOP="com.nexa.xr.STOP_CONTROLLERS";public static volatile boolean running;
    private final ControllerPacket.State state=new ControllerPacket.State();private final Object lock=new Object();private final List<View> windows=new ArrayList<>();
    private WindowManager manager;private ScheduledExecutorService sender;private DatagramSocket socket;private InetAddress loopback;private ScheduledFuture<?> publishing;private volatile boolean stopping;
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null||STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}if(running)return START_NOT_STICKY;
        if(!Settings.canDrawOverlays(this)){stopSelf();return START_NOT_STICKY;}
        try {
            NotificationManager notifications=getSystemService(NotificationManager.class);notifications.createNotificationChannel(new NotificationChannel("nexa-controllers","Controles na tela",NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop=PendingIntent.getService(this,3,new Intent(this,ControllerOverlayService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent open=PendingIntent.getActivity(this,4,new Intent(this,HomeActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification notification=new Notification.Builder(this,"nexa-controllers").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("NEXA · controles virtuais ativos").setContentText("Toque para abrir o NEXA ou parar os controles.").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"Parar",stop).build()).build();
            if(Build.VERSION.SDK_INT>=34)startForeground(43,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(43,notification);
            manager=(WindowManager)getSystemService(WINDOW_SERVICE);createPanel(0);createPanel(1);createToolbar();running=true;
            sender=Executors.newSingleThreadScheduledExecutor();publishing=sender.scheduleAtFixedRate(()->{
                try {
                    if(socket==null){loopback=InetAddress.getByAddress(new byte[]{127,0,0,1});socket=new DatagramSocket(new InetSocketAddress(loopback,0));}
                    byte[] data;synchronized(lock){data=ControllerPacket.encode(state,true);}socket.send(new DatagramPacket(data,data.length,loopback,42424));
                }catch(Exception e){new Handler(Looper.getMainLooper()).post(()->{if(!stopping){RuntimeDiagnostics.write(this,"controller-last.txt","Falha no envio local: "+e);stopSelf();}});}
            },0,33,TimeUnit.MILLISECONDS);
        }catch(Exception e){RuntimeDiagnostics.write(this,"controller-last.txt","Não foi possível iniciar controles: "+e);stopSelf();}
        return START_NOT_STICKY;
    }
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density);}
    private void window(View view,int width,int gravity,int y){WindowManager.LayoutParams p=new WindowManager.LayoutParams(dp(width),WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);p.gravity=gravity;p.y=dp(y);p.alpha=0.78f;manager.addView(view,p);windows.add(view);}
    private void createToolbar(){LinearLayout bar=new LinearLayout(this);bar.setBackgroundColor(Color.rgb(10,20,35));Button reset=new Button(this);reset.setText("CENTRAR MÃOS");reset.setOnClickListener(v->{synchronized(lock){Arrays.fill(state.yaw,0);Arrays.fill(state.pitch,0);}});bar.addView(reset,new LinearLayout.LayoutParams(0,dp(42),1));Button close=new Button(this);close.setText("PARAR");close.setOnClickListener(v->stopSelf());bar.addView(close,new LinearLayout.LayoutParams(0,dp(42),1));window(bar,260,Gravity.TOP|Gravity.CENTER_HORIZONTAL,0);}
    private void createPanel(int hand){LinearLayout panel=new LinearLayout(this);panel.setOrientation(1);panel.setBackgroundColor(Color.rgb(10,20,35));TextView label=new TextView(this);label.setText(hand==0?"MÃO ESQUERDA":"MÃO DIREITA");label.setTextColor(Color.WHITE);panel.addView(label);
        panel.addView(new AimPad(hand),new LinearLayout.LayoutParams(-1,dp(48)));
        LinearLayout row=new LinearLayout(this);hold(row,hand,hand==0?"X":"A",1);hold(row,hand,hand==0?"Y":"B",2);panel.addView(row);
        row=new LinearLayout(this);hold(row,hand,"GAT",4);hold(row,hand,"PEG",8);if(hand==0)hold(row,hand,"MENU",16);else hold(row,hand,"STICK",32);panel.addView(row);
        panel.addView(new Stick(hand),new LinearLayout.LayoutParams(-1,dp(105)));window(panel,190,Gravity.BOTTOM|(hand==0?Gravity.LEFT:Gravity.RIGHT),0);
    }
    private void hold(LinearLayout row,int hand,String name,int bit){Button button=new Button(this);button.setText(name);button.setTextSize(11);button.setPadding(0,0,0,0);button.setMinWidth(0);button.setMinimumWidth(0);
        button.setOnTouchListener((v,e)->{int action=e.getActionMasked();synchronized(lock){if(action==MotionEvent.ACTION_DOWN)state.buttons[hand]|=bit;else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)state.buttons[hand]&=~bit;}if(action==MotionEvent.ACTION_UP)v.performClick();return true;});row.addView(button,new LinearLayout.LayoutParams(0,dp(38),1));}
    private final class AimPad extends View {
        final int hand;float x,y;AimPad(int hand){super(ControllerOverlayService.this);this.hand=hand;setContentDescription("Arraste para apontar a mão");}
        @Override protected void onDraw(Canvas canvas){Paint p=new Paint(3);p.setColor(Color.CYAN);p.setTextSize(dp(13));canvas.drawText("ARRASTE PARA APONTAR",dp(8),dp(28),p);}
        @Override public boolean onTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x=e.getX();y=e.getY();return true;}if(e.getActionMasked()==MotionEvent.ACTION_MOVE){synchronized(lock){state.yaw[hand]=Math.max(-(float)Math.PI,Math.min((float)Math.PI,state.yaw[hand]-(e.getX()-x)/dp(100)));state.pitch[hand]=Math.max(-1.45f,Math.min(1.45f,state.pitch[hand]-(e.getY()-y)/dp(100)));}x=e.getX();y=e.getY();}if(e.getActionMasked()==MotionEvent.ACTION_UP)performClick();return true;}
        @Override public boolean performClick(){super.performClick();return true;}
    }
    private final class Stick extends View {
        final int hand;float x,y;Stick(int hand){super(ControllerOverlayService.this);this.hand=hand;setContentDescription("Analógico virtual");}
        @Override protected void onDraw(Canvas c){Paint p=new Paint(3);p.setColor(Color.GRAY);float r=Math.min(getWidth(),getHeight())*0.42f;c.drawCircle(getWidth()/2f,getHeight()/2f,r,p);p.setColor(Color.CYAN);c.drawCircle(getWidth()/2f+x*r,getHeight()/2f-y*r,dp(12),p);}
        @Override public boolean onTouchEvent(MotionEvent e){int action=e.getActionMasked();if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){x=y=0;performClick();}else {float r=Math.max(1,Math.min(getWidth(),getHeight())*0.42f);x=(e.getX()-getWidth()/2f)/r;y=(getHeight()/2f-e.getY())/r;float length=(float)Math.hypot(x,y);if(length>1){x/=length;y/=length;}}
            synchronized(lock){state.stickX[hand]=x;state.stickY[hand]=y;}invalidate();return true;}
        @Override public boolean performClick(){super.performClick();return true;}
    }
    @Override public void onDestroy(){stopping=true;running=false;for(View view:windows)try{manager.removeView(view);}catch(RuntimeException ignored){}windows.clear();
        if(sender!=null){if(publishing!=null)publishing.cancel(false);sender.execute(()->{if(socket!=null){try{byte[] neutral=ControllerPacket.encode(state,false);socket.send(new DatagramPacket(neutral,neutral.length,loopback,42424));}catch(Exception ignored){}socket.close();}});sender.shutdown();}
        stopForeground(true);super.onDestroy();}
}
