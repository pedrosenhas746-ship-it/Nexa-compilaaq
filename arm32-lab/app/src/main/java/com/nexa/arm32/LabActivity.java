// SPDX-License-Identifier: GPL-2.0-only
package com.nexa.arm32;
import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;

public final class LabActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView report;private Button select,cpu;private File folder;private volatile boolean busy;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        folder=new File(getCacheDir(),"arm32-"+java.util.UUID.randomUUID());folder.mkdirs();
        ScrollView scroll=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(1);
        int pad=(int)(20*getResources().getDisplayMetrics().density);box.setPadding(pad,pad,pad,pad);box.setBackgroundColor(Color.rgb(8,15,25));scroll.addView(box);
        TextView title=new TextView(this);title.setText("NEXA ARM32 Lab");title.setTextSize(25);title.setTextColor(Color.WHITE);box.addView(title);
        TextView intro=new TextView(this);intro.setText("Protótipo de execução de 32 bits. Testa o processador emulado e partes do APK. Ainda não abre o jogo.");intro.setTextSize(16);intro.setTextColor(Color.LTGRAY);box.addView(intro);
        cpu=new Button(this);cpu.setText("Testar motor ARM32");box.addView(cpu);cpu.setOnClickListener(v->test(null));
        select=new Button(this);select.setText("Testar APK do GTAG");box.addView(select);select.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");startActivityForResult(i,1);});
        Button copy=new Button(this);copy.setText("Copiar resultado");box.addView(copy);copy.setOnClickListener(v->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("NEXA ARM32",report.getText()));Toast.makeText(this,"Resultado copiado",Toast.LENGTH_SHORT).show();});
        report=new TextView(this);report.setTextColor(Color.WHITE);report.setTextSize(15);report.setTextIsSelectable(true);report.setPadding(0,pad,0,0);
        report.setText("Selecione o APK original de 32 bits. Não é necessário instalar o jogo.\n\nMotor: Unicorn 2.1.4 (GPLv2). Código: github.com/pedrosenhas746-ship-it/Nexa-compilaaq/tree/quest-native-bridge-v3/arm32-lab");box.addView(report);setContentView(scroll);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==1&&result==RESULT_OK&&data!=null&&data.getData()!=null)test(data.getData());}
    private void test(Uri uri){if(busy)return;busy=true;cpu.setEnabled(false);select.setEnabled(false);report.setText("Executando teste…");
        worker.execute(()->{String result;File input=new File(folder,"input.apk");try{
            String header=Build.MANUFACTURER+" "+Build.MODEL+" • Android "+Build.VERSION.RELEASE+"\nHost: "+java.util.Arrays.toString(Build.SUPPORTED_ABIS)+"\n\n";
            if(uri==null)result=header+NativeProbe.selfTest();
            else{try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(input)){if(in==null)throw new IOException("APK inacessível");ApkProbe.copy(in,out,1024L*1024*1024);}result=header+ApkProbe.inspect(input,folder);}
        }catch(Exception|LinkageError e){result="Teste interrompido: "+e.getMessage();}finally{input.delete();}
        String text=result;runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()){report.setText(text);busy=false;cpu.setEnabled(true);select.setEnabled(true);}});
        });
    }
    @Override protected void onDestroy(){worker.shutdownNow();if(isFinishing()){
        File cleanup=folder;new Thread(()->{try{if(worker.awaitTermination(10,TimeUnit.SECONDS)){File[] files=cleanup.listFiles();if(files!=null)for(File f:files)f.delete();cleanup.delete();}}catch(InterruptedException ignored){}},"arm32-cleanup").start();
    }super.onDestroy();}
}
