package com.nexa.xr;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.List;

/** Quest apps can expose only the Oculus VR category instead of LAUNCHER. */
final class QuestLaunchResolver {
    static ComponentName resolve(PackageManager pm, String packageName) {
        try {
            Intent launcher = pm.getLaunchIntentForPackage(packageName);
            if (launcher != null && launcher.getComponent() != null) return launcher.getComponent();
            Intent vr = new Intent(Intent.ACTION_MAIN).addCategory("com.oculus.intent.category.VR").setPackage(packageName);
            List<ResolveInfo> returned = pm.queryIntentActivities(vr, 0);
            if (returned == null || returned.isEmpty()) return null;
            List<ActivityInfo> activities = new ArrayList<>();
            for (ResolveInfo resolved : returned) {
                if (resolved == null || resolved.activityInfo == null) continue;
                ActivityInfo info = resolved.activityInfo;
                if (info.name != null && info.applicationInfo != null && info.exported && info.enabled && info.applicationInfo.enabled)
                    activities.add(info);
            }
            activities.sort((a, b) -> a.name.compareTo(b.name));
            return activities.isEmpty() ? null : new ComponentName(packageName, activities.get(0).name);
        } catch (RuntimeException e) {
            android.util.Log.w("NEXA", "Cannot resolve launch activity for " + packageName, e);
            return null;
        }
    }
    private QuestLaunchResolver() {}
}
