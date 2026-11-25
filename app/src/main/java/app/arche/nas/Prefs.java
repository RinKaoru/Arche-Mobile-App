package app.arche.nas;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Réglages et état de l'app (stockés sur le téléphone). */
public final class Prefs {
    private final SharedPreferences p;

    public Prefs(Context c) { p = c.getApplicationContext().getSharedPreferences("arche", Context.MODE_PRIVATE); }

    public String installId() {
        String id = p.getString("install_id", null);
        if (id == null) { id = UUID.randomUUID().toString().replace("-", ""); p.edit().putString("install_id", id).apply(); }
        return id;
    }

    public String host() { return BuildConfig.PUBLIC_HOST; }
    public String lanHost() { return p.getString("lan_host", BuildConfig.LAN_HOST); }
    public void setLanHost(String h) { if (h != null && !h.isEmpty()) p.edit().putString("lan_host", h).apply(); }

    // association au compte
    public String token() { return p.getString("token", null); }
    public int userId() { return p.getInt("user_id", 0); }
    public String userName() { return p.getString("user_name", ""); }
    public boolean paired() { return token() != null; }
    public void pair(String token, int userId, String userName) {
        p.edit().putString("token", token).putInt("user_id", userId).putString("user_name", userName).apply();
    }
    public void unpair() { p.edit().remove("token").remove("user_id").remove("user_name").putBoolean("enabled", false).apply(); }

    // options de sauvegarde
    public boolean enabled() { return p.getBoolean("enabled", false); }
    public void setEnabled(boolean v) {
        SharedPreferences.Editor e = p.edit().putBoolean("enabled", v);
        if (v && p.getLong("enabled_since", 0) == 0) e.putLong("enabled_since", System.currentTimeMillis() / 1000);
        e.apply();
    }
    public long enabledSince() { return p.getLong("enabled_since", 0); }
    public boolean wifiOnly() { return p.getBoolean("wifi_only", false); }
    public boolean chargingOnly() { return p.getBoolean("charging_only", false); }
    public boolean videos() { return p.getBoolean("videos", true); }
    public boolean includeExisting() { return p.getBoolean("include_existing", true); }
    public void setBool(String k, boolean v) { p.edit().putBoolean(k, v).apply(); }

    /** Albums choisis (BUCKET_ID). null = jamais choisi → choix par défaut (appareil photo, captures, WhatsApp). */
    public Set<String> albums() { Set<String> s = p.getStringSet("albums", null); return s == null ? null : new HashSet<>(s); }
    public void setAlbums(Set<String> s) { p.edit().putStringSet("albums", new HashSet<>(s)).apply(); }

    // état de la dernière sauvegarde (affiché dans Arche)
    public JSONObject stats() {
        try { return new JSONObject(p.getString("stats", "{}")); } catch (Exception e) { return new JSONObject(); }
    }
    public void setStats(JSONObject o) { p.edit().putString("stats", o.toString()).apply(); }
    public long lastBackup() { return p.getLong("last_backup", 0); }
    public void setLastBackup(long t) { p.edit().putLong("last_backup", t).apply(); }

    // notifications Arche déjà affichées
    public long lastNotif() { return p.getLong("last_notif", -1); }
    public void setLastNotif(long id) { p.edit().putLong("last_notif", id).apply(); }
    public long lastUpdateCheck() { return p.getLong("last_update_check", 0); }
    public void setLastUpdateCheck(long t) { p.edit().putLong("last_update_check", t).apply(); }

    static JSONArray toJson(Set<String> s) { JSONArray a = new JSONArray(); if (s != null) for (String x : s) a.put(x); return a; }
}
