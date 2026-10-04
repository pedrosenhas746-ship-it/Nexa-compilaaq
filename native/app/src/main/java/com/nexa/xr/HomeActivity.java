package com.nexa.xr;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import com.nexa.xr.importer.ImportActivity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** The phone menu opens independently of sensors, the VR canvas, and installed-app scanning. */
public final class HomeActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final List<QuestAppProfile> apps=new ArrayList<>();
    private TextView status;
    private ListView list;
    private Button scan,launch;
    private int selected=-1;
    private String requestedPackage;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(8,15,25));
        int pad=(int)(16*getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);
        TextView title=new TextView(this);title.setText("NEXA Quest Bridge v4.2.1");title.setTextColor(Color.WHITE);title.setTextSize(24);root.addView(title);
        button(root,"IMPORTAR APK",v->startActivity(new Intent(this,ImportActivity.class)));
        scan=button(root,"BUSCAR JOGOS INSTALADOS",v->scanApps());
        launch=button(root,"EXECUTAR SELECIONADO",v->launchSelected());launch.setEnabled(false);
        button(root,"CONFIGURAR RUNTIME",v->startActivity(new Intent(this,RuntimeSetupActivity.class)));
        button(root,"VERIFICAR RUNTIME",v->{UniversalRuntimeManager runtime=new UniversalRuntimeManager(this);if(!runtime.launchRuntimeProbe())status.setText(runtime.lastError());});
        button(root,"CONTROLES NA TELA",v->controllers());
        button(root,"MENU VR",v->startActivity(new Intent(this,MainActivity.class)));
        button(root,"COPIAR DIAGNÓSTICO",v->copyDiagnostic());
        status=new TextView(this);status.setTextColor(Color.WHITE);status.setTextSize(15);status.setTextIsSelectable(true);status.setPadding(0,pad/2,0,pad/2);root.addView(status);
        list=new ListView(this);list.setBackgroundColor(Color.WHITE);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((parent,view,index,id)->{selected=index;launch.setEnabled(true);status.setText(apps.get(index).label+"\n"+apps.get(index).notes);});
        setContentView(root);
        status.setText(new File(getFilesDir(),"last-crash.txt").isFile()?"Foi registrado um fechamento anterior. Toque COPIAR DIAGNÓSTICO para enviar o erro.":"Pronto. Importe um APK ou busque os jogos já instalados.");
        handleSelection(getIntent());
    }
    private Button button(LinearLayout root,String title,View.OnClickListener listener) {Button b=new Button(this);b.setText(title);b.setOnClickListener(listener);root.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleSelection(intent);}
    private void handleSelection(Intent intent){requestedPackage=intent.getStringExtra("nexa.select_package");if(requestedPackage!=null)scanApps();}
    private void scanApps(){
        if(!scan.isEnabled())return;scan.setEnabled(false);launch.setEnabled(false);status.setText("Buscando jogos…");
        worker.execute(()->{
            try {
                List<QuestAppProfile> found=new QuestCompatibilityScanner(this).scanInstalledApps();
                runOnUiThread(()->{if(isFinishing()||isDestroyed())return;apps.clear();apps.addAll(found);selected=-1;
                    List<String> names=new ArrayList<>();for(int i=0;i<apps.size();i++){QuestAppProfile p=apps.get(i);names.add(p.label+"\n"+p.shortSummary());if(p.packageName.equals(requestedPackage))selected=i;}
                    requestedPackage=null;list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,names));scan.setEnabled(true);launch.setEnabled(selected>=0);
                    if(selected>=0){list.setSelection(selected);status.setText("Selecionado: "+apps.get(selected).label);}else status.setText(found.size()+" aplicativos encontrados. Toque em um para selecionar.");
                });
            }catch(Exception error){showError("Não foi possível buscar os jogos",error);runOnUiThread(()->scan.setEnabled(true));}
        });
    }
    private void launchSelected(){
        if(selected<0||selected>=apps.size())return;
        try {UniversalRuntimeManager runtime=new UniversalRuntimeManager(this);QuestAppProfile app=apps.get(selected);
            if(runtime.launch(app))status.setText("Abrindo "+app.label);else status.setText(runtime.lastError());
        }catch(Exception error){showError("Não foi possível abrir o jogo",error);}
    }
    private void showError(String message,Exception error){android.util.Log.e("NEXA",message,error);runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())status.setText(message+": "+error);});}
    private void controllers() {
        if(com.nexa.xr.input.ControllerOverlayService.running){stopService(new Intent(this,com.nexa.xr.input.ControllerOverlayService.class));status.setText("Controles parados.");return;}
        if(!android.provider.Settings.canDrawOverlays(this)){startActivityForResult(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:"+getPackageName())),92);return;}
        startControllers();
    }
    private void startControllers() {
        try {startForegroundService(new Intent(this,com.nexa.xr.input.ControllerOverlayService.class));status.setText("Controles virtuais iniciados para teste no PhoneXR. Arraste para apontar, use os analógicos e segure os botões. Recebimento no jogo ainda precisa de teste.");}
        catch(Exception e){showError("Não foi possível iniciar os controles",e);}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==92){if(android.provider.Settings.canDrawOverlays(this))startControllers();else status.setText("Permita aparecer sobre outros apps para usar os controles virtuais.");}}
    private void copyDiagnostic(){
        String report=RuntimeDiagnostics.collect(this,status.getText().toString());
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("NEXA diagnóstico",report));Toast.makeText(this,"Diagnóstico copiado",Toast.LENGTH_SHORT).show();
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
