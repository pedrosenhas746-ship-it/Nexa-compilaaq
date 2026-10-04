package com.nexa.xr;

import android.content.*;
import android.content.pm.*;
import android.os.IBinder;
import android.os.Build;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Diagnostic only: a Binder connection is not an OpenXR instance/session or input acknowledgement. */
public final class RuntimeConnectionCheck {
    public static final String PACKAGE="org.freedesktop.monado.openxr_runtime.out_of_process";
    public static final ComponentName SERVICE=new ComponentName(PACKAGE,"org.freedesktop.monado.ipc.MonadoService");
    private RuntimeConnectionCheck() {}
    public static String packageCheck(Context context,String pkg) {
        StringBuilder text=new StringBuilder("Pré-verificação do pacote: ").append(pkg).append('\n');
        try {
            ApplicationInfo info=context.getPackageManager().getApplicationInfo(pkg,0);
            text.append("Pacote visível: sim; habilitado: ").append(info.enabled).append("\nAPK: ").append(info.sourceDir)
                .append("\nBibliotecas: ").append(info.nativeLibraryDir).append('\n');
            Context runtime=context.createPackageContext(pkg,Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
            for(String name:new String[]{"org.freedesktop.monado.auxiliary.ActivityLifecycleListener","org.freedesktop.monado.ipc.Client"}) {
                try {Class<?> type=runtime.getClassLoader().loadClass(name);text.append("Classe ").append(type.getSimpleName()).append(": disponível\n");}
                catch(Throwable e){text.append("Classe ").append(name).append(": ").append(e).append('\n');}
            }
        }catch(Exception e){text.append("Falha ao acessar pacote/classes: ").append(e).append('\n');}
        text.append("Carregar classes aqui não confirma o carregamento feito pelo código nativo.\n");return text.toString();
    }
    /** Call on a worker thread so the main thread can dispatch service callbacks. Always unbind. */
    public static String bindCheck(Context context,ComponentName component,long timeoutMillis) {
        StringBuilder report=new StringBuilder("Serviço: ").append(component.flattenToString()).append('\n');
        CountDownLatch completed=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();
        ServiceConnection connection=new ServiceConnection(){
            private void finish(String value){result.compareAndSet(null,value);completed.countDown();}
            @Override public void onServiceConnected(ComponentName name,IBinder binder){finish(binder!=null&&binder.isBinderAlive()?"Binder conectado":"Binder inválido");}
            @Override public void onServiceDisconnected(ComponentName name){finish("Serviço desconectado antes de concluir");}
            @Override public void onNullBinding(ComponentName name){finish("Serviço retornou Binder nulo");}
            @Override public void onBindingDied(ComponentName name){finish("Ligação com o serviço morreu");}
        };
        boolean bound=false;
        try {
            ServiceInfo info=context.getPackageManager().getServiceInfo(component,0);
            report.append("Declarado: sim; exportado: ").append(info.exported).append("; habilitado: ").append(info.enabled).append('\n');
            int flags=Context.BIND_AUTO_CREATE|Context.BIND_IMPORTANT|Context.BIND_DEBUG_UNBIND;
            if(Build.VERSION.SDK_INT>=30)flags|=Context.BIND_INCLUDE_CAPABILITIES;
            bound=context.bindService(new Intent("org.freedesktop.monado.ipc.CONNECT").setComponent(component),connection,flags);
            if(!bound)report.append("Vincular serviço: recusado/não encontrado\n");
            else if(!completed.await(Math.max(1,Math.min(timeoutMillis,5000)),TimeUnit.MILLISECONDS))report.append("Vincular serviço: sem resposta no prazo\n");
            else report.append("Vincular serviço: ").append(result.get()).append('\n');
        }catch(InterruptedException e){Thread.currentThread().interrupt();report.append("Vincular serviço: interrompido\n");}
        catch(Exception e){report.append("Vincular serviço: ").append(e).append('\n');}
        finally {if(bound)try{context.unbindService(connection);}catch(RuntimeException e){report.append("Liberar ligação: ").append(e).append('\n');}}
        report.append("Binder conectado não confirma funcionamento de VR, câmera ou controles.\n");return report.toString();
    }
}
