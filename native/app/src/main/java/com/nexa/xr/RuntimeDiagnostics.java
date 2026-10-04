package com.nexa.xr;
import android.content.Context;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class RuntimeDiagnostics {
    public static void write(Context context,String name,String text) {
        try(FileOutputStream out=context.openFileOutput(name,Context.MODE_PRIVATE)){out.write(text.getBytes(StandardCharsets.UTF_8));}
        catch(IOException e){android.util.Log.w("NEXA","Cannot save diagnostic",e);}
    }
    public static String collect(Context context,String status) {
        UniversalRuntimeManager manager=new UniversalRuntimeManager(context);
        StringBuilder report=new StringBuilder("NEXA 23.4.2\nDevice: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append("\nAndroid: ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT)
            .append("\nABIs: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append("\n").append(manager.getDeviceCaps().summary())
            .append("\n").append(manager.runtimeSummary()).append("\nStatus: ").append(status);
        for(String name:new String[]{"last-import.txt","controller-last.txt","runtime-last.txt","last-crash.txt"}) {
            File file=new File(context.getFilesDir(),name);if(!file.isFile())continue;
            try(InputStream in=new FileInputStream(file)){report.append("\n\n").append(name).append("\n").append(new String(com.nexa.xr.importer.ApkRewriter.readBounded(in,256*1024),StandardCharsets.UTF_8));}
            catch(IOException e){report.append("\nErro ao ler diagnóstico: ").append(e);}
        }
        return report.toString();
    }
}
