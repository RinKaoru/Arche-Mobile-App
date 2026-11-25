package app.arche.nas;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pont JavaScript « window.ArcheApp » : la page Synchronisation d'Arche affiche et règle la sauvegarde du téléphone.
 * N'accepte que les appels venant d'une page du vrai serveur Arche (HTTPS, bon nom).
 */
public final class Bridge {
    private final MainActivity a;
    private final Prefs prefs;
    private final Map<String, String> thumbs = new HashMap<>();

    Bridge(MainActivity a) { this.a = a; this.prefs = a.prefs; }

    private boolean ok() { return prefs.host().equalsIgnoreCase(a.currentHost); }

    @JavascriptInterface public String version() {
        try { return new JSONObject().put("name", BuildConfig.VERSION_NAME).put("code", BuildConfig.VERSION_CODE).toString(); } catch (Exception e) { return "{}"; }
    }
    @JavascriptInterface public String installId() { return ok() ? prefs.installId() : ""; }
    @JavascriptInterface public String deviceName() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "Android" : Build.MODEL;
        return (model.toLowerCase().startsWith(m.toLowerCase()) ? model : capitalize(m) + " " + model).trim();
    }
    @JavascriptInterface public int pairedUser() { return ok() && prefs.paired() ? prefs.userId() : 0; }

    @JavascriptInterface public void setToken(String token, int userId, String userName) {
        if (!ok() || token == null || !token.startsWith("arche_app_")) return;
        if (prefs.userId() != userId) Done.get(a).clear();   // autre compte : on repart de zéro
        prefs.pair(token, userId, userName);
        Scheduler.schedule(a);
    }

    @JavascriptInterface public String state() {
        if (!ok()) return "{}";
        try {
            JSONObject o = new JSONObject();
            o.put("paired", prefs.paired()).put("user_id", prefs.userId()).put("enabled", prefs.enabled())
             .put("wifi_only", prefs.wifiOnly()).put("charging_only", prefs.chargingOnly()).put("videos", prefs.videos())
             .put("include_existing", prefs.includeExisting()).put("permission", Perms.media(a)).put("location", Perms.location(a))
             .put("notifications", Perms.notifications(a)).put("battery", Perms.battery(a)).put("manufacturer", Build.MANUFACTURER)
             .put("stats", prefs.stats()).put("last_backup", prefs.lastBackup()).put("running", BackupWorker.running())
             .put("version", BuildConfig.VERSION_NAME).put("version_code", BuildConfig.VERSION_CODE);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    /** Albums du téléphone avec vignette et choix actuel. */
    @JavascriptInterface public String albums() {
        if (!ok() || "denied".equals(Perms.media(a))) return "[]";
        try {
            Set<String> chosen = Media.chosenAlbums(a, prefs);
            JSONArray out = new JSONArray();
            List<Media.Album> list = Media.albums(a, prefs.videos());
            for (Media.Album al : list) {
                String t = thumbs.get(al.id + ":" + al.coverId);
                if (t == null && thumbs.size() < 200) { t = Media.thumb(a, al); if (t != null) thumbs.put(al.id + ":" + al.coverId, t); }
                out.put(new JSONObject().put("id", al.id).put("name", al.name).put("path", al.path).put("count", al.count)
                        .put("enabled", chosen.contains(al.id)).put("suggested", Media.isDefault(al)).put("thumb", t == null ? "" : t));
            }
            return out.toString();
        } catch (Exception e) { return "[]"; }
    }

    @JavascriptInterface public void setAlbum(String id, boolean on) {
        if (!ok()) return;
        Set<String> s = Media.chosenAlbums(a, prefs);
        if (on) s.add(id); else s.remove(id);
        prefs.setAlbums(s);
        if (prefs.enabled()) Scheduler.now(a);
    }

    @JavascriptInterface public void setAllAlbums(boolean on) {
        if (!ok()) return;
        Set<String> s = new java.util.HashSet<>();
        if (on) for (Media.Album al : Media.albums(a, true)) s.add(al.id);
        prefs.setAlbums(s);
        if (prefs.enabled()) Scheduler.now(a);
    }

    @JavascriptInterface public void setEnabled(boolean on) {
        if (!ok() || !prefs.paired()) return;
        if (on && ("denied".equals(Perms.media(a)) || !Perms.location(a) || !Perms.notifications(a))) {
            a.enableAfterPerms = true;
            a.runOnUiThread(() -> Perms.request(a, MainActivity.REQ_PERMS));
            if ("denied".equals(Perms.media(a))) return;
        }
        prefs.setEnabled(on);
        Scheduler.schedule(a);
        if (on) Scheduler.now(a);
    }

    @JavascriptInterface public void setOption(String key, boolean v) {
        if (!ok()) return;
        if (!key.equals("wifi_only") && !key.equals("charging_only") && !key.equals("videos") && !key.equals("include_existing")) return;
        prefs.setBool(key, v);
        Scheduler.schedule(a);
        if (prefs.enabled()) Scheduler.now(a);
    }

    @JavascriptInterface public void requestPermissions() { if (ok()) a.runOnUiThread(() -> Perms.request(a, MainActivity.REQ_PERMS)); }

    @JavascriptInterface public void openBattery() {
        if (!ok()) return;
        a.runOnUiThread(() -> {
            try { a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.getPackageName()))); }
            catch (Exception e) { openAppSettings(); }
        });
    }

    @JavascriptInterface public void openAppSettings() {
        if (!ok()) return;
        a.runOnUiThread(() -> a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName()))));
    }

    @JavascriptInterface public void backupNow() { if (ok() && prefs.enabled()) Scheduler.now(a); }

    @JavascriptInterface public void unpair() {
        if (!ok()) return;
        prefs.unpair(); Done.get(a).clear(); Scheduler.schedule(a);
    }

    @JavascriptInterface public void installUpdate() { if (ok()) new Thread(() -> a.result(Updater.install(a))).start(); }

    private static String capitalize(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
