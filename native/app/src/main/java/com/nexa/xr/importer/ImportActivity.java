package com.nexa.xr.importer;

import android.app.*;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import com.nexa.xr.HomeActivity;
import java.io.*;
import java.util.concurrent.*;

/** A phone UI for file selection, adaptation, Android installation, and launch handoff. */
public final class ImportActivity extends Activity {
    private static final int PICK=81,ALLOW_INSTALL=82;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status;
    private Button choose,install,launch;
    private ProgressBar progress;
    private File folder,input,prepared;
    private DeviceApkImporter importer;
    private DeviceApkImporter.Analysis analysis;
    private boolean busy;
    private String installedPackage;
    @Override public void onCreate(Bundle state){super.onCreate(state);buildUi();folder=new File(getCacheDir(),"quest-import-"+java.util.UUID.randomUUID());folder.mkdirs();importer=new DeviceApkImporter(this,folder);handleInstallStatus(getIntent());}
    private void buildUi(){
        ScrollView scroll=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);int p=(int)(20*getResources().getDisplayMetrics().density);box.setPadding(p,p,p,p);box.setBackgroundColor(Color.rgb(8,15,25));scroll.addView(box);
        TextView title=new TextView(this);title.setText("Importar jogo do Quest");title.setTextSize(25);title.setTextColor(Color.WHITE);box.addView(title);
        TextView intro=new TextView(this);intro.setText("Selecione um APK completo. O NEXA verifica o celular, prepara uma cópia e abre o instalador do Android. Compatibilidade experimental.");intro.setTextColor(Color.LTGRAY);intro.setTextSize(16);intro.setPadding(0,p/2,0,p/2);box.addView(intro);
        choose=button(box,"1. Selecionar APK",v->pick());
        install=button(box,"2. Instalar cópia",v->install());install.setEnabled(false);
        launch=button(box,"Abrir no NEXA",v->openNexa());launch.setEnabled(false);
        progress=new ProgressBar(this);progress.setVisibility(View.GONE);box.addView(progress);
        status=new TextView(this);status.setText("Pronto para selecionar. PhoneXR e driver VrApi devem estar instalados para executar jogos VrApi.");status.setTextSize(16);status.setTextColor(Color.WHITE);status.setTextIsSelectable(true);status.setPadding(0,p,0,p);box.addView(status);setContentView(scroll);
    }
    private Button button(LinearLayout box,String text,View.OnClickListener click){Button b=new Button(this);b.setText(text);b.setOnClickListener(click);box.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
    private void pick(){if(busy)return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,PICK);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==PICK&&result==RESULT_OK&&data!=null&&data.getData()!=null)read(data.getData());else if(request==ALLOW_INSTALL&&prepared!=null&&!busy) { if(getPackageManager().canRequestPackageInstalls()) install(); else status.setText("Permita instalar apps desta fonte nas configurações para continuar."); }}
    private void read(Uri uri){
        analysis=null;prepared=null;installedPackage=null;setBusy(true);status.setText("Copiando APK…");
        worker.execute(()->{try{
            for(File f:folder.listFiles()==null?new File[0]:folder.listFiles())if(f.isFile())f.delete();
            File file=new File(folder,"source.apk");try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new BufferedOutputStream(new FileOutputStream(file))){if(in==null)throw new IOException("Arquivo inacessível");ApkRewriter.copy(in,out,8L*1024*1024*1024);}
            input=file;analysis=importer.inspect(input,this::message);
            prepared=importer.prepare(input,analysis,this::message);
            ui(()->{setBusy(false);status.setText("Adaptação automática concluída e assinatura verificada.\n\n"+analysis.summary()+"\n\nToque em Instalar cópia para testar.");});
        }catch(Exception e){fail(e);}});
    }
    private void install(){
        if(busy||prepared==null)return;
        if(!getPackageManager().canRequestPackageInstalls()) {startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())),ALLOW_INSTALL);return;}
        setBusy(true);message("Enviando cópia ao instalador Android…");
        worker.execute(()->{int id=-1;PackageInstaller installer=getPackageManager().getPackageInstaller();try{
            PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);params.setAppPackageName(analysis.packageName);params.setSize(prepared.length());
            if(Build.VERSION.SDK_INT>=31)params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            id=installer.createSession(params);
            try(PackageInstaller.Session session=installer.openSession(id);InputStream in=new FileInputStream(prepared);OutputStream out=session.openWrite("base.apk",0,prepared.length())){
                ApkRewriter.copy(in,out,prepared.length());session.fsync(out);
            }
            Intent callback=new Intent(this,ImportActivity.class).setAction(getPackageName()+".IMPORT_RESULT");callback.putExtra("nexa.package",analysis.packageName);callback.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags=PendingIntent.FLAG_UPDATE_CURRENT;if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;
            PendingIntent pending=PendingIntent.getActivity(this,id,callback,flags);
            try(PackageInstaller.Session session=installer.openSession(id)){session.commit(pending.getIntentSender());}
            ui(()->{setBusy(false);status.setText("Confirme a instalação na tela do Android.");});
        }catch(Exception e){if(id!=-1)installer.abandonSession(id);fail(e);}});
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleInstallStatus(intent);}
    private void handleInstallStatus(Intent i){
        if(i==null||!i.hasExtra(PackageInstaller.EXTRA_STATUS))return;
        int s=i.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        setBusy(false);
        if(s==PackageInstaller.STATUS_PENDING_USER_ACTION){Intent confirm=i.getParcelableExtra(Intent.EXTRA_INTENT);if(confirm!=null)startActivity(confirm);else status.setText("Android não forneceu a confirmação. Tente instalar novamente.");}
        else if(s==PackageInstaller.STATUS_SUCCESS){installedPackage=i.getStringExtra("nexa.package");launch.setEnabled(true);status.setText("Jogo instalado. Toque em Abrir no NEXA para testar pelo runtime.");}
        else{status.setText("Instalação não concluída: "+i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)+"\n\nSe houver original com outra assinatura, o Android impede a atualização. Seus saves não foram removidos.");}
    }
    private void openNexa(){Intent i=new Intent(this,HomeActivity.class);i.putExtra("nexa.select_package",installedPackage);i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(i);finish();}
    private void setBusy(boolean value){busy=value;choose.setEnabled(!value);install.setEnabled(!value&&prepared!=null);launch.setEnabled(!value&&installedPackage!=null);progress.setVisibility(value?View.VISIBLE:View.GONE);}
    private void ui(Runnable r){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())r.run();});}
    private void message(String m){ui(()->status.setText(m));}
    private void fail(Exception e){ui(()->{setBusy(false);status.setText("Não foi possível preparar: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));});}
    @Override protected void onDestroy(){
        worker.shutdownNow();
        if(isFinishing()&&folder!=null) {
            File cleanup=folder;
            new Thread(()->{try{if(worker.awaitTermination(10,TimeUnit.SECONDS)) {File[]files=cleanup.listFiles();if(files!=null)for(File f:files)f.delete();cleanup.delete();}}catch(InterruptedException ignored){}},"nexa-cache-cleanup").start();
        }
        super.onDestroy();
    }
}
