package app.arche.nas;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.os.BatteryManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Sauvegarde des photos et vidéos vers Arche, comme Google Photos :
 * nouvelles photos envoyées automatiquement, doublons ignorés, grosses vidéos envoyées par morceaux (reprise possible).
 */
public class BackupWorker extends Worker {
    static final int NOTIF_ID = 42;
    private static final long DIRECT_MAX = 64L * 1024 * 1024, CHUNK = 48L * 1024 * 1024;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final MediaType JSON = MediaType.parse("application/json");

    private final Prefs prefs;
    private final OkHttpClient http;
    private boolean foreground = false;
    private long lastNotif = 0;

    public BackupWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
        prefs = new Prefs(c);
        http = Net.client(c);
    }

    public static boolean running() { return RUNNING.get(); }

    @NonNull @Override public Result doWork() {
        if (!prefs.paired()) return Result.success();
        if (!RUNNING.compareAndSet(false, true)) return Result.success();   // une seule sauvegarde à la fois
        String kind = getInputData().getString("kind");
        JSONObject st = prefs.stats();
        try {
            Net.networkChanged();
            Notifs.poll(getApplicationContext(), prefs, http);
            Updater.check(getApplicationContext(), prefs, http, false);
            if (!prefs.enabled()) { put(st, "running", false); return done(st, Result.success()); }
            if ("denied".equals(Perms.media(getApplicationContext()))) { put(st, "last_error", "permission"); put(st, "running", false); return done(st, Result.success()); }
            if (prefs.wifiOnly() && metered()) { put(st, "waiting", "wifi"); put(st, "running", false); return done(st, Result.success()); }
            if (prefs.chargingOnly() && !charging() && !"now".equals(kind)) { put(st, "waiting", "charge"); put(st, "running", false); return done(st, Result.success()); }
            st.remove("waiting");
            return backup(st, kind);
        } catch (Exception e) {
            put(st, "last_error", e.getMessage());
            put(st, "running", false);
            return done(st, Result.retry());
        } finally {
            RUNNING.set(false);
            if (!"periodic".equals(kind)) Scheduler.armTrigger(getApplicationContext(), false);
        }
    }

    private Result backup(JSONObject st, String kind) throws Exception {
        Context c = getApplicationContext();
        Set<String> chosen = Media.chosenAlbums(c, prefs);
        List<Media.Item> all = Media.items(c, chosen, prefs.videos());
        if (!prefs.includeExisting()) {
            List<Media.Item> recent = new ArrayList<>();
            for (Media.Item it : all) if (it.added >= prefs.enabledSince()) recent.add(it);
            all = recent;
        }
        Map<Long, String> done = Done.get(c).all();
        List<Media.Item> todo = new ArrayList<>();
        for (Media.Item it : all) if (!Done.key(it).equals(done.get(it.id))) todo.add(it);

        // 1) le NAS les a peut-être déjà (ancienne sauvegarde manuelle, autre téléphone) : on vérifie par lots
        for (int i = 0; i < todo.size(); i += 500) {
            List<Media.Item> part = todo.subList(i, Math.min(todo.size(), i + 500));
            JSONArray items = new JSONArray();
            for (Media.Item it : part) items.put(new JSONObject().put("name", it.name).put("size", it.size));
            try (Response r = http.newCall(Net.authed(prefs, "/api/photos/check").post(RequestBody.create(new JSONObject().put("items", items).toString(), JSON)).build()).execute()) {
                if (r.code() == 401) return revoked(st);
                if (!r.isSuccessful() || r.body() == null) break;
                JSONArray known = new JSONObject(r.body().string()).optJSONArray("known");
                for (int k = 0; known != null && k < known.length(); k++) Done.get(c).mark(part.get(known.getInt(k)));
            }
        }
        done = Done.get(c).all();
        List<Media.Item> left = new ArrayList<>();
        for (Media.Item it : todo) if (!Done.key(it).equals(done.get(it.id))) left.add(it);

        put(st, "on_phone", all.size());
        put(st, "pending", left.size());
        put(st, "backed_up", all.size() - left.size());
        put(st, "running", !left.isEmpty());
        prefs.setStats(st);
        if (left.isEmpty()) { st.remove("last_error"); return done(st, Result.success()); }

        try { setForegroundAsync(info(0, left.size(), 0)).get(); foreground = true; }
        catch (Exception e) { foreground = false; }      // Android peut refuser en arrière-plan : on continue par tranches
        long start = System.currentTimeMillis();
        int sent = 0, errors = 0; long bytes = 0;
        for (Media.Item it : left) {
            if (isStopped()) break;
            if (!foreground && System.currentTimeMillis() - start > 8 * 60_000) { Scheduler.cont(c); break; }
            try {
                int code = upload(it);
                if (code == 401) return revoked(st);
                if (code >= 200 && code < 300) { Done.get(c).mark(it); sent++; bytes += it.size; }
                else if (code >= 400 && code < 500 && code != 408 && code != 429) { Done.get(c).mark(it); errors++; }  // refus définitif : on n'insiste pas
                else errors++;
            } catch (IOException e) {
                // réseau coupé : on garde ce qui est fait et on réessaie plus tard
                put(st, "last_error", "Réseau indisponible");
                finish(st, all.size(), left.size() - sent, errors, sent);
                return done(st, Result.retry());
            }
            progress(sent + errors, left.size(), bytes);
        }
        st.remove("last_error");
        if (errors > 0) put(st, "last_error", errors + " fichier(s) refusé(s)");
        finish(st, all.size(), left.size() - sent - errors, errors, sent);
        return done(st, Result.success());
    }

    private void finish(JSONObject st, int onPhone, int pending, int errors, int sent) {
        pending = Math.max(0, pending);
        put(st, "on_phone", onPhone);
        put(st, "backed_up", onPhone - pending);
        put(st, "pending", pending);
        put(st, "errors", errors);
        put(st, "running", false);
        if (sent > 0) prefs.setLastBackup(System.currentTimeMillis() / 1000);
    }

    /** Envoie un fichier ; renvoie le code HTTP final. */
    private int upload(Media.Item it) throws IOException {
        Context c = getApplicationContext();
        long mtime = it.taken > 0 ? it.taken : it.modified * 1000;
        String q = "?name=" + enc(it.name) + "&mtime=" + mtime + "&album=" + enc(it.album == null ? "" : it.album);
        android.net.Uri uri = Media.original(c, it);
        if (it.size <= DIRECT_MAX) {
            try (Response r = http.newCall(Net.authed(prefs, "/api/photos/backup" + q).put(new StreamBody(c.getContentResolver(), uri, 0, it.size)).build()).execute()) {
                return r.code();
            }
        }
        // gros fichier : morceaux de 48 Mo, reprise là où on s'était arrêté (même identifiant d'envoi)
        String uid = "app" + prefs.installId().substring(0, 12) + "-" + it.id + "-" + it.size;
        long off = 0;
        try (Response r = http.newCall(Net.authed(prefs, "/api/chunks/" + uid).get().build()).execute()) {
            if (r.code() == 401) return 401;
            if (r.isSuccessful() && r.body() != null) off = new JSONObject(r.body().string()).optLong("size", 0);
        } catch (org.json.JSONException ignored) {}
        if (off > it.size) off = 0;
        while (off < it.size) {
            if (isStopped()) throw new IOException("arrêté");
            long n = Math.min(CHUNK, it.size - off);
            try (Response r = http.newCall(Net.authed(prefs, "/api/chunks/" + uid + "?offset=" + off)
                    .put(new StreamBody(c.getContentResolver(), uri, off, n)).build()).execute()) {
                if (!r.isSuccessful() || r.body() == null) return r.code();
                off = new JSONObject(r.body().string()).optLong("size", off + n);
            } catch (org.json.JSONException e) { throw new IOException(e); }
            progress(-1, -1, 0);
        }
        try (Response r = http.newCall(Net.authed(prefs, "/api/photos/backup" + q + "&chunk=" + uid + "&total=" + it.size)
                .put(RequestBody.create(new byte[0], null)).build()).execute()) {
            return r.code();
        }
    }

    private Result revoked(JSONObject st) {
        prefs.unpair();
        Done.get(getApplicationContext()).clear();
        put(st, "last_error", "revoked"); put(st, "running", false);
        Notifs.show(getApplicationContext(), 7, Notifs.CH_ARCHE, "Sauvegarde arrêtée", "Ce téléphone a été déconnecté d'Arche. Ouvre l'app pour te reconnecter.", "/synchro");
        return done(st, Result.failure());
    }

    private Result done(JSONObject st, Result r) {
        prefs.setStats(st);
        report(st);
        return r;
    }

    private void report(JSONObject st) {
        if (!prefs.paired()) return;
        try {
            JSONObject o = new JSONObject(st.toString());
            o.put("enabled", prefs.enabled()).put("wifi_only", prefs.wifiOnly()).put("charging_only", prefs.chargingOnly())
             .put("app_version", BuildConfig.VERSION_NAME).put("albums", Prefs.toJson(Media.chosenAlbums(getApplicationContext(), prefs)).length());
            if (prefs.lastBackup() > 0) o.put("last_backup", prefs.lastBackup());
            try (Response r = http.newCall(Net.authed(prefs, "/api/app/report").post(RequestBody.create(o.toString(), JSON)).build()).execute()) { /* rien */ }
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- notification de progression
    private void progress(int doneN, int total, long bytes) {
        long now = System.currentTimeMillis();
        if (now - lastNotif < 1000 || doneN < 0) return;
        lastNotif = now;
        JSONObject st = prefs.stats();
        put(st, "pending", Math.max(0, total - doneN)); put(st, "running", true);
        prefs.setStats(st);
        if (foreground) setForegroundAsync(info(doneN, total, bytes));
    }

    @NonNull @Override public ForegroundInfo getForegroundInfo() { return info(0, 0, 0); }

    private ForegroundInfo info(int doneN, int total, long bytes) {
        Context c = getApplicationContext();
        PendingIntent open = PendingIntent.getActivity(c, 1, new Intent(c, MainActivity.class).putExtra("path", "/synchro"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, Notifs.CH_BACKUP)
                .setSmallIcon(R.drawable.ic_upload)
                .setContentTitle(total > 0 ? "Sauvegarde des photos" : "Arche")
                .setContentText(total > 0 ? String.format(Locale.FRANCE, "%d sur %d", doneN, total) : "Vérification des nouvelles photos…")
                .setProgress(Math.max(total, 0), doneN, total <= 0)
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .build();
        if (Build.VERSION.SDK_INT >= 29) return new ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        return new ForegroundInfo(NOTIF_ID, n);
    }

    // ---------------------------------------------------------------- conditions
    private boolean metered() {
        ConnectivityManager cm = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm == null || cm.isActiveNetworkMetered();
    }

    private boolean charging() {
        Intent b = getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int s = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL;
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    static void put(JSONObject o, String k, Object v) { try { o.put(k, v); } catch (Exception ignored) {} }
}
