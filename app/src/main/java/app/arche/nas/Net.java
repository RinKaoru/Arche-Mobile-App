package app.arche.nas;

import android.content.Context;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * Réseau : toujours en HTTPS vers le vrai nom (certificat vérifié normalement).
 * À la maison, le nom est simplement résolu vers l'adresse locale du NAS : envoi direct, à pleine vitesse,
 * sans passer par Internet ni par la limite de 100 Mo de Cloudflare.
 */
public final class Net {
    private static volatile Boolean home = null;
    private static volatile long homeCheckedAt = 0;
    private static OkHttpClient client;

    public static synchronized OkHttpClient client(Context c) {
        if (client == null) {
            final Prefs prefs = new Prefs(c);
            client = new OkHttpClient.Builder()
                    .dns(new Dns() {
                        @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
                            if (hostname.equalsIgnoreCase(prefs.host()) && atHome(prefs)) {
                                return Collections.singletonList(InetAddress.getByName(prefs.lanHost()));
                            }
                            return Dns.SYSTEM.lookup(hostname);
                        }
                    })
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(180, TimeUnit.SECONDS)
                    .writeTimeout(180, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build();
        }
        return client;
    }

    /** Le NAS répond-il sur le réseau local ? (vérifié au plus toutes les 60 s) */
    public static boolean atHome(Prefs prefs) {
        long now = System.currentTimeMillis();
        if (home != null && now - homeCheckedAt < 60_000) return home;
        boolean ok;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(prefs.lanHost(), BuildConfig.PORT), 700);
            ok = true;
        } catch (IOException e) { ok = false; }
        home = ok; homeCheckedAt = now;
        return ok;
    }

    public static void networkChanged() { home = null; }

    public static String base(Prefs prefs) { return "https://" + prefs.host() + (BuildConfig.PORT == 443 ? "" : ":" + BuildConfig.PORT); }

    public static Request.Builder authed(Prefs prefs, String path) {
        return new Request.Builder().url(base(prefs) + path)
                .header("Authorization", "Bearer " + prefs.token())
                .header("X-NAS", "1")
                .header("User-Agent", "ArcheApp/" + BuildConfig.VERSION_NAME + " Android");
    }
}
