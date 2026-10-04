package com.nexa.xr;
import android.app.Activity;
import android.content.*;
import android.os.*;
import android.graphics.Color;
import android.hardware.*;
import android.view.Surface;
import android.widget.*;
import java.util.concurrent.*;

/** Runs in a separate process to isolate loader initialization and runtime failures. */
public final class RuntimeProbeActivity extends Activity implements SensorEventListener {
    private TextView status,sensors;private SensorManager manager;private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private native static String probe(Activity activity);
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(24,24,24,24);box.setBackgroundColor(Color.rgb(8,15,25));
        TextView title=new TextView(this);title.setText("Verificar runtime VR");title.setTextSize(24);title.setTextColor(Color.WHITE);box.addView(title);
        sensors=new TextView(this);sensors.setTextColor(Color.WHITE);sensors.setText("Aguardando sensor de orientação do celular…");box.addView(sensors);
        Button copy=new Button(this);copy.setText("COPIAR RESULTADO");copy.setOnClickListener(v->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("NEXA runtime",RuntimeDiagnostics.collect(this,status.getText().toString())));Toast.makeText(this,"Resultado copiado",Toast.LENGTH_SHORT).show();});box.addView(copy);
        ScrollView scroll=new ScrollView(this);status=new TextView(this);status.setTextColor(Color.WHITE);status.setTextIsSelectable(true);status.setText("Conectando ao runtime OpenXR…");scroll.addView(status);box.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(box);
        manager=(SensorManager)getSystemService(SENSOR_SERVICE);
        if(manager!=null){Sensor sensor=manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);if(sensor==null)sensor=manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);if(sensor!=null)manager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_UI);else sensors.setText("Celular sem sensor de orientação disponível.");}
        Handler handler=new Handler(Looper.getMainLooper());handler.postDelayed(()->{if(!isFinishing()&&status.getText().toString().equals("Conectando ao runtime OpenXR…"))status.setText("O runtime ainda não respondeu. Você pode voltar e copiar o diagnóstico.");},20000);
        worker.execute(()->{String result;try{System.loadLibrary("nexa_runtime_probe");result=probe(this);}catch(Throwable e){result="Falha na verificação: "+e;}
            RuntimeDiagnostics.write(this,"runtime-last.txt",result);final String text=result;
            runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())status.setText(text);});});
    }
    @Override public void onSensorChanged(SensorEvent event) {
        float[] rotation=new float[9],adjusted=new float[9],angles=new float[3];SensorManager.getRotationMatrixFromVector(rotation,event.values);
        int screen=getWindowManager().getDefaultDisplay().getRotation();int x=SensorManager.AXIS_X,y=SensorManager.AXIS_Y;
        if(screen==Surface.ROTATION_90){x=SensorManager.AXIS_Y;y=SensorManager.AXIS_MINUS_X;}else if(screen==Surface.ROTATION_180){x=SensorManager.AXIS_MINUS_X;y=SensorManager.AXIS_MINUS_Y;}else if(screen==Surface.ROTATION_270){x=SensorManager.AXIS_MINUS_Y;y=SensorManager.AXIS_X;}
        SensorManager.remapCoordinateSystem(rotation,x,y,adjusted);SensorManager.getOrientation(adjusted,angles);
        sensors.setText(String.format(java.util.Locale.ROOT,"Sensor do celular: %.1f° / %.1f° / %.1f°\nEsses valores ainda não confirmam movimento dentro do jogo.",Math.toDegrees(angles[0]),Math.toDegrees(angles[1]),Math.toDegrees(angles[2])));
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
    @Override protected void onDestroy(){if(manager!=null)manager.unregisterListener(this);worker.shutdownNow();super.onDestroy();}
}
