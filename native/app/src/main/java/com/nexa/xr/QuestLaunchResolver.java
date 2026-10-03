package com.nexa.xr;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.List;

/** Quest apps can expose only the Oculus VR category instead of LAUNCHER. */
final class QuestLaunchResolver {
    static ComponentName resolve(PackageManager pm, String packageName) {
        Intent launcher = pm.getLaunchIntentForPackage(packageName);
        if (launcher != null && launcher.getComponent() != null) return launcher.getComponent();
        Intent vr = new Intent(Intent.ACTION_MAIN).addCategory("com.oculus.intent.category.VR")
                .setPackage(packageName);
        List<ResolveInfo> activities = pm.queryIntentActivities(vr, 0);
        activities.sort((a, b) -> a.activityInfo.name.compareTo(b.activityInfo.name));
        for (ResolveInfo resolved : activities) {
            ActivityInfo info = resolved.activityInfo;
            if (info.exported && info.enabled && info.applicationInfo.enabled)
                return new ComponentName(packageName, info.name);
        }
        return null;
    }
    private QuestLaunchResolver() {}
}
