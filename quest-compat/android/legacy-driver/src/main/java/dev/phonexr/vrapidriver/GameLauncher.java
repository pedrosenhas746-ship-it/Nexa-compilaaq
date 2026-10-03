package dev.phonexr.vrapidriver;
import android.app.Activity;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/** Matches the launcher name in the supplied PhoneXR binary; forwards only its visibility URI. */
public final class GameLauncher extends Activity {
    private static final String RUNTIME_AUTHORITY="org.freedesktop.monado.phonexr.visibility";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String target=getIntent().getStringExtra("component");
            ComponentName component=target==null?null:ComponentName.unflattenFromString(target);
            if(component==null || getPackageName().equals(component.getPackageName()))
                throw new IllegalArgumentException("Selecione uma atividade de jogo");
            ActivityInfo info=getPackageManager().getActivityInfo(component,0);
            if(!info.exported || !info.enabled || !info.applicationInfo.enabled)
                throw new SecurityException("Atividade indisponível");
            Uri own=new Uri.Builder().scheme("content").authority(VisibilityProvider.AUTHORITY)
                .appendPath(component.getPackageName()).build();
            ClipData clips=ClipData.newRawUri("Horizon Bridge driver",own);
            ClipData received=getIntent().getClipData();
            if(received!=null) for(int i=0;i<received.getItemCount() && i<8;i++) {
                Uri uri=received.getItemAt(i).getUri();
                if(uri!=null && "content".equals(uri.getScheme()) && RUNTIME_AUTHORITY.equals(uri.getAuthority()) &&
                    checkCallingOrSelfUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)==PackageManager.PERMISSION_GRANTED)
                    clips.addItem(new ClipData.Item(uri));
            }
            Intent launch=new Intent(Intent.ACTION_MAIN).setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            launch.setClipData(clips);
            startActivity(launch);
        } catch(PackageManager.NameNotFoundException|RuntimeException e) {
            Toast.makeText(this,"Falha na abertura VrApi: "+e.getClass().getSimpleName(),Toast.LENGTH_LONG).show();
            android.util.Log.e("HB-VrApiDriver","Launch failed",e);
        } finally { finish(); }
    }
}
