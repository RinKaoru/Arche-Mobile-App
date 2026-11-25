package app.arche.nas;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Mises à jour : l'app se met à jour elle-même depuis Arche (pas de Play Store). */
public final class Updater {
    /** Version disponible sur Arche (null si pas plus récente). */
    static JSONObject available(Context c, OkHttpClient http) {
        Prefs p = new Prefs(c);
        try (Response r = http.newCall(new Request.Builder().url(Net.base(p) + "/api/app/android").build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) return null;
            JSONObject m = new JSONObject(r.body().string());
            return m.optInt("version_code") > BuildConfig.VERSION_CODE ? m : null;
        } catch (Exception e) { return null; }
    }

    static void check(Context c, Prefs prefs, OkHttpClient http, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - prefs.lastUpdateCheck() < 6 * 3600_000L) return;
        prefs.setLastUpdateCheck(now);
        JSONObject m = available(c, http);
        if (m != null) Notifs.show(c, 9, Notifs.CH_UPDATE, "Mise à jour d'Arche disponible",
                "Version " + m.optString("version_name") + " : touche pour l'installer.", "#update");
    }

    /** Télécharge, vérifie l'empreinte et lance l'installation (à appeler hors du fil principal). */
    static String install(Activity a) {
        try {
            OkHttpClient http = Net.client(a);
            JSONObject m = available(a, http);
            if (m == null) return "uptodate";
            if (!a.getPackageManager().canRequestPackageInstalls()) {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
                return "permission";
            }
            File dir = new File(a.getCacheDir(), "updates"); dir.mkdirs();
            File f = new File(dir, "arche.apk");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (Response r = http.newCall(new Request.Builder().url(Net.base(new Prefs(a)) + "/api/app/android.apk").build()).execute()) {
                if (!r.isSuccessful() || r.body() == null) return "error";
                try (InputStream in = r.body().byteStream(); FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); md.update(buf, 0, n); }
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            if (!hex.toString().equals(m.optString("sha256"))) { f.delete(); return "corrupt"; }
            Uri u = FileProvider.getUriForFile(a, a.getPackageName() + ".files", f);
            a.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
            return "ok";
        } catch (Exception e) { return "error"; }
    }
}
