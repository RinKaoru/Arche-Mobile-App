package app.arche.nas;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;

/** Autorisations : photos/vidéos, lieu des photos, notifications, batterie. */
public final class Perms {
    static boolean has(Context c, String p) { return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    /** "granted" (toutes les photos), "partial" (Android 14 : seulement une sélection), "denied". */
    public static String media(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (has(c, Manifest.permission.READ_MEDIA_IMAGES)) return "granted";
            if (Build.VERSION.SDK_INT >= 34 && has(c, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) return "partial";
            return "denied";
        }
        return has(c, Manifest.permission.READ_EXTERNAL_STORAGE) ? "granted" : "denied";
    }

    public static boolean location(Context c) { return Build.VERSION.SDK_INT < 29 || has(c, Manifest.permission.ACCESS_MEDIA_LOCATION); }

    public static boolean notifications(Context c) { return Build.VERSION.SDK_INT < 33 || has(c, Manifest.permission.POST_NOTIFICATIONS); }

    public static boolean battery(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    public static void request(Activity a, int code) {
        List<String> l = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            l.add(Manifest.permission.READ_MEDIA_IMAGES); l.add(Manifest.permission.READ_MEDIA_VIDEO);
            l.add(Manifest.permission.POST_NOTIFICATIONS);
        } else l.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= 29) l.add(Manifest.permission.ACCESS_MEDIA_LOCATION);
        a.requestPermissions(l.toArray(new String[0]), code);
    }
}
