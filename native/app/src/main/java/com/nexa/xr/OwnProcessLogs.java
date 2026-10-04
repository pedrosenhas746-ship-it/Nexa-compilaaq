package com.nexa.xr;

import android.os.Process;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Reads only this probe process's bounded recent logs; requires no READ_LOGS permission. */
public final class OwnProcessLogs {
    private OwnProcessLogs() {}
    public static String collect() {
        StringBuffer text=new StringBuffer();java.lang.Process command=null;
        try {
            command=new ProcessBuilder("logcat","-d","-v","brief","--pid="+Process.myPid(),"-t","120").redirectErrorStream(true).start();
            final java.lang.Process process=command;
            Thread reader=new Thread(()->{
                try(Reader input=new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8)){
                    char[] buffer=new char[1024];int n;
                    while(text.length()<24000&&(n=input.read(buffer,0,Math.min(buffer.length,24000-text.length())))!=-1)text.append(buffer,0,n);
                }catch(IOException e){text.append("\nLeitura dos logs: ").append(e.getClass().getSimpleName());}
            },"nexa-own-log-reader");reader.setDaemon(true);reader.start();reader.join(2000);
            if(reader.isAlive()){process.destroy();reader.join(200);text.append("\nColeta interrompida pelo prazo.");}
            if(text.length()==0)text.append("Nenhuma linha disponível para este processo.");
        }catch(InterruptedException e){Thread.currentThread().interrupt();text.append("Coleta interrompida.");}
        catch(Exception e){text.append("Logs indisponíveis: ").append(e);}
        finally {if(command!=null)command.destroy();}
        return "Logs apenas do processo do teste (PID "+Process.myPid()+"):\n"+text;
    }
}
