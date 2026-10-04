package org.freedesktop.monado.ipc;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import java.util.concurrent.*;

/** Executes in PhoneXR's own process so its native server errors are readable without READ_LOGS. */
public final class NexaDiagnosticActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView text;
    private Button copy;
    private String report;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(16*getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);
        TextView title=new TextView(this);title.setText("Diagnóstico do PhoneXR");title.setTextSize(22);root.addView(title);
        TextView help=new TextView(this);help.setText("Execute VERIFICAR RUNTIME no NEXA antes de copiar este relatório. Ele mostra os erros do servidor do PhoneXR.");root.addView(help);
        Button refresh=new Button(this);refresh.setText("ATUALIZAR RELATÓRIO");refresh.setOnClickListener(v->collect());root.addView(refresh);
        copy=new Button(this);copy.setText("COPIAR RELATÓRIO");copy.setEnabled(false);copy.setOnClickListener(v->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("PhoneXR",report));Toast.makeText(this,"Relatório copiado",Toast.LENGTH_SHORT).show();});root.addView(copy);
        ScrollView scroll=new ScrollView(this);text=new TextView(this);text.setTextIsSelectable(true);scroll.addView(text);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);collect();
    }
    private void collect() {
        copy.setEnabled(false);text.setText("Lendo os logs do servidor…");
        worker.execute(()->{
            StringBuilder result=new StringBuilder("PhoneXR NEXA IPC repair\n").append(java.time.Instant.now()).append("\nDevice: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\nAndroid: ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append("\nSobreposição: ").append(Settings.canDrawOverlays(this)).append('\n');
            try {result.append("Versão: ").append(getPackageManager().getPackageInfo(getPackageName(),0).versionName).append('\n');}catch(Exception e){result.append(e).append('\n');}
            try {result.append("Página de memória: ").append(android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)).append(" bytes\n");}catch(Exception e){result.append(e).append('\n');}
            result.append(NexaRuntimeLogs.collect());
            String collected=result.toString();runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()){report=collected;text.setText(report);copy.setEnabled(true);}});
        });
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
