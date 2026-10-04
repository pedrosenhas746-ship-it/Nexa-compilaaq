package com.nexa.xr;
import android.app.*;import android.content.*;import android.content.pm.*;import android.graphics.Color;import android.net.Uri;import android.os.*;import android.provider.Settings;import android.widget.*;import com.nexa.xr.importer.ApkRewriter;
import java.io.*;import java.util.concurrent.*;

/** Android still asks for install confirmation; no automatic uninstall of existing runtimes. */
public final class RuntimeSetupActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private TextView status;private String pendingAsset,pendingPackage;private boolean busy;
    @Override public void onCreate(Bundle state){super.onCreate(state);LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(24,24,24,24);box.setBackgroundColor(Color.rgb(8,15,25));TextView title=new TextView(this);title.setText("Configurar runtime VR");title.setTextSize(24);title.setTextColor(Color.WHITE);box.addView(title);
        button(box,"INSTALAR PHONEXR CORRIGIDO","phonexr-runtime.apk","org.freedesktop.monado.openxr_runtime.out_of_process");
        button(box,"INSTALAR / ATUALIZAR DRIVER VRAPI","vrapi-driver.apk","com.oculus.systemdriver");
        Button settings=new Button(this);settings.setText("ABRIR CONFIGURAÇÕES DO PHONEXR");settings.setOnClickListener(v->{try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+RuntimeConnectionCheck.PACKAGE)));}catch(Exception e){status.setText("PhoneXR não encontrado nas configurações: "+e.getMessage());}});box.addView(settings);
        status=new TextView(this);status.setTextColor(Color.WHITE);status.setTextIsSelectable(true);box.addView(status);setContentView(box);handle(getIntent());}
    private void button(LinearLayout root,String text,String asset,String pkg){Button b=new Button(this);b.setText(text);b.setOnClickListener(v->{if(!busy){pendingAsset=asset;pendingPackage=pkg;install();}});root.addView(b);}
    @Override protected void onResume(){super.onResume();if(!busy&&pendingAsset==null)status.setText(new UniversalRuntimeManager(this).runtimeSummary()+"\n\nPhoneXR 1.1.1-nexa-ipc corrige a conexão Java. Para substituir o PhoneXR original, abra as configurações acima e desinstale somente o PhoneXR; a assinatura mudou e suas configurações serão apagadas. Depois instale o corrigido e autorize a sobreposição novamente. A correção ainda precisa de teste no jogo.");}
    private void install(){if(busy||pendingAsset==null)return;if(!getPackageManager().canRequestPackageInstalls()){startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())),91);return;}
        busy=true;status.setText("Preparando instalador Android…");String asset=pendingAsset,pkg=pendingPackage;worker.execute(()->{PackageInstaller installer=getPackageManager().getPackageInstaller();int id=-1;File copy=new File(getCacheDir(),"runtime-"+asset);try{
            try(InputStream in=getAssets().open("runtime/"+asset);OutputStream out=new FileOutputStream(copy)){ApkRewriter.copy(in,out,32*1024*1024);}
            PackageInfo info=getPackageManager().getPackageArchiveInfo(copy.getPath(),0);if(info==null||!pkg.equals(info.packageName))throw new IOException("Pacote de runtime inválido");
            PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);params.setAppPackageName(pkg);params.setSize(copy.length());if(Build.VERSION.SDK_INT>=31)params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);id=installer.createSession(params);
            try(PackageInstaller.Session session=installer.openSession(id);InputStream in=new FileInputStream(copy);OutputStream out=session.openWrite("base.apk",0,copy.length())){ApkRewriter.copy(in,out,copy.length());session.fsync(out);}
            Intent callback=new Intent(this,RuntimeSetupActivity.class).setAction(getPackageName()+".RUNTIME_INSTALL");callback.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags=PendingIntent.FLAG_UPDATE_CURRENT;if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;
            PendingIntent result=PendingIntent.getActivity(this,id,callback,flags);try(PackageInstaller.Session session=installer.openSession(id)){session.commit(result.getIntentSender());}
            ui(()->{busy=false;status.setText("Confirme a instalação na tela do Android.");});
        }catch(Exception e){if(id!=-1)installer.abandonSession(id);ui(()->{busy=false;pendingAsset=null;status.setText("Não foi possível instalar: "+e.getMessage());});}finally{copy.delete();}});}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==91){if(getPackageManager().canRequestPackageInstalls())install();else{pendingAsset=null;status.setText("Autorize esta fonte nas configurações para instalar o runtime.");}}}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handle(intent);}
    private void handle(Intent intent){if(intent==null||!intent.hasExtra(PackageInstaller.EXTRA_STATUS))return;busy=false;int code=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);if(code==PackageInstaller.STATUS_PENDING_USER_ACTION){Intent confirm=intent.getParcelableExtra(Intent.EXTRA_INTENT);if(confirm!=null)startActivity(confirm);else status.setText("Android não forneceu confirmação.");}else{pendingAsset=null;status.setText(code==PackageInstaller.STATUS_SUCCESS?"Instalação concluída.\n"+new UniversalRuntimeManager(this).runtimeSummary():"Instalação não concluída: "+intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)+"\nUma assinatura diferente impede atualizar o pacote existente. Nenhum app foi desinstalado.");}}
    private void ui(Runnable r){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())r.run();});}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
