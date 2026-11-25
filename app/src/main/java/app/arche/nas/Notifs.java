package app.arche.nas;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Response;

/** Canaux de notification + affichage des notifications d'Arche (relevées à chaque passage du travail de fond). */
public final class Notifs {
    static final String CH_BACKUP = "backup", CH_ARCHE = "arche", CH_UPDATE = "update";

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel b = new NotificationChannel(CH_BACKUP, "Sauvegarde des photos", NotificationManager.IMPORTANCE_LOW);
        b.setDescription("Progression de la sauvegarde"); b.setShowBadge(false);
        NotificationChannel a = new NotificationChannel(CH_ARCHE, "Notifications d'Arche", NotificationManager.IMPORTANCE_DEFAULT);
        a.setDescription("Fichiers reçus, partages, alertes…");
        NotificationChannel u = new NotificationChannel(CH_UPDATE, "Mises à jour de l'app", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(b); nm.createNotificationChannel(a); nm.createNotificationChannel(u);
    }

    static void show(Context c, int id, String channel, String title, String body, String path) {
        if (!Perms.notifications(c)) return;
        Intent i = new Intent(c, MainActivity.class).putExtra("path", path == null ? "/" : path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, id, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, channel).setSmallIcon(R.drawable.ic_notif).setContentTitle(title)
                .setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body)).setAutoCancel(true)
                .setContentIntent(pi).setColor(0xFFF28A12).build();
        c.getSystemService(NotificationManager.class).notify(id, n);
    }

    /** Affiche les notifications d'Arche arrivées depuis la dernière fois (sans inonder au premier lancement). */
    static void poll(Context c, Prefs prefs, OkHttpClient http) {
        try (Response r = http.newCall(Net.authed(prefs, "/api/notifications").get().build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) return;
            JSONArray a = new JSONArray(r.body().string());
            long last = prefs.lastNotif(), max = last;
            int shown = 0;
            for (int i = 0; i < a.length(); i++) {
                JSONObject n = a.getJSONObject(i);
                long id = n.optLong("id");
                max = Math.max(max, id);
                if (last >= 0 && id > last && n.optInt("read") == 0 && shown < 5) {
                    show(c, 1000 + (int) (id % 100000), CH_ARCHE, n.optString("title", "Arche"), n.optString("body", ""), n.optString("link", "/notifications"));
                    shown++;
                }
            }
            prefs.setLastNotif(Math.max(max, 0));
        } catch (Exception ignored) {}
    }
}
