package com.nexa.xr;
import android.app.Application;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Keeps a local crash report for user-initiated copying on the next launch. */
public final class NexaApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error) -> {
            try {
                StringWriter stack = new StringWriter(); error.printStackTrace(new PrintWriter(stack));
                String report="NEXA 23.4.1\nDevice: "+Build.MANUFACTURER+" "+Build.MODEL+"\nAndroid: "+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT+"\nThread: "+thread.getName()+"\nTime: "+System.currentTimeMillis()+"\n\n"+stack;
                try(FileOutputStream out=new FileOutputStream(new File(getFilesDir(),"last-crash.txt"))) {out.write(report.getBytes(StandardCharsets.UTF_8));}
            } catch(Exception ignored) {}
            if(previous!=null)previous.uncaughtException(thread,error);
            else {android.os.Process.killProcess(android.os.Process.myPid());System.exit(1);}
        });
    }
}
