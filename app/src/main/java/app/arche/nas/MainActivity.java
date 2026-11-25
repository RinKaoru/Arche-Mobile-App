package app.arche.nas;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/** L'app : l'interface d'Arche en plein écran + le pont vers les fonctions du téléphone (sauvegarde des photos). */
public class MainActivity extends Activity {
    static final int REQ_FILES = 10, REQ_PERMS = 11;
    WebView web;
    Prefs prefs;
    private ValueCallback<Uri[]> fileCb;
    volatile String currentHost = "";
    boolean enableAfterPerms = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        web = new WebView(this);
        web.setBackgroundColor(0xFF09090B);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUserAgentString(s.getUserAgentString() + " ArcheApp/" + BuildConfig.VERSION_NAME);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.addJavascriptInterface(new Bridge(this), "ArcheApp");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("https".equals(u.getScheme()) && prefs.host().equalsIgnoreCase(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;   // liens externes : dans le navigateur
            }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                Uri u = Uri.parse(url);
                currentHost = "https".equals(u.getScheme()) && u.getHost() != null ? u.getHost() : "";
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) offline();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                String[] types = p.getAcceptTypes();
                if (types != null && types.length > 0 && types[0] != null && !types[0].isEmpty()) {
                    if (types.length == 1) i.setType(types[0]); else i.putExtra(Intent.EXTRA_MIME_TYPES, types);
                    if (types[0].contains(",")) { i.setType("*/*"); i.putExtra(Intent.EXTRA_MIME_TYPES, types[0].split(",")); }
                }
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(Intent.createChooser(i, "Choisir des fichiers"), REQ_FILES); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        web.setDownloadListener((url, ua, disp, mime, len) -> {
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                String name = URLUtil.guessFileName(url, disp, mime);
                r.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                r.addRequestHeader("User-Agent", ua);
                r.setTitle(name).setMimeType(mime)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                getSystemService(DownloadManager.class).enqueue(r);
                Toast.makeText(this, "Téléchargement de " + name + "…", Toast.LENGTH_SHORT).show();
            } catch (Exception e) { Toast.makeText(this, "Téléchargement impossible", Toast.LENGTH_LONG).show(); }
        });

        if (b != null) web.restoreState(b); else open(getIntent());
    }

    void open(Intent i) {
        String path = i == null ? null : i.getStringExtra("path");
        Uri data = i == null ? null : i.getData();
        if ("#update".equals(path)) { path = "/synchro"; new Thread(() -> result(Updater.install(this))).start(); }
        String url = Net.base(prefs) + (path != null && path.startsWith("/") ? path : "/");
        if (data != null && prefs.host().equalsIgnoreCase(data.getHost())) url = data.toString();
        web.loadUrl(url);
    }

    void result(String r) {
        runOnUiThread(() -> {
            if ("uptodate".equals(r)) Toast.makeText(this, "L'app est à jour", Toast.LENGTH_SHORT).show();
            else if ("permission".equals(r)) Toast.makeText(this, "Autorise Arche à installer des mises à jour, puis réessaie.", Toast.LENGTH_LONG).show();
            else if (!"ok".equals(r)) Toast.makeText(this, "Mise à jour impossible pour l'instant", Toast.LENGTH_LONG).show();
        });
    }

    void offline() {
        String html = "<html><head><meta name=viewport content='width=device-width,initial-scale=1'><style>body{margin:0;height:100vh;display:grid;place-items:center;"
                + "background:#09090B;color:#F2F2F4;font-family:sans-serif;text-align:center}p{color:#A1A1AA;margin:8px 24px 22px}"
                + "a{display:inline-block;background:#F28A12;color:#170c02;padding:12px 22px;border-radius:999px;text-decoration:none;font-weight:600}</style></head>"
                + "<body><div><div style='font-size:42px'>☁️</div><h2>Arche est injoignable</h2><p>Vérifie ta connexion Internet. La sauvegarde des photos reprendra toute seule dès que possible.</p>"
                + "<a href='" + Net.base(prefs) + "/'>Réessayer</a></div>"
                + "<script>setTimeout(function(){location.href='" + Net.base(prefs) + "/'},6000)</script></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    /** Prévient la page qu'un état natif a changé (autorisation accordée, etc.). */
    void notifyWeb() { runOnUiThread(() -> web.evaluateJavascript("window.dispatchEvent(new Event('archeapp'))", null)); }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); open(i); }

    @Override protected void onResume() { super.onResume(); Net.networkChanged(); notifyWeb(); }

    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); web.saveState(o); }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else moveTaskToBack(true);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILES || fileCb == null) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null) {
            ClipData cd = data.getClipData();
            if (cd != null) { out = new Uri[cd.getItemCount()]; for (int k = 0; k < out.length; k++) out[k] = cd.getItemAt(k).getUri(); }
            else if (data.getData() != null) out = new Uri[]{data.getData()};
        }
        fileCb.onReceiveValue(out);
        fileCb = null;
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_PERMS) {
            if (enableAfterPerms && !"denied".equals(Perms.media(this))) {
                prefs.setEnabled(true); Scheduler.schedule(this); Scheduler.now(this);
            }
            enableAfterPerms = false;
            notifyWeb();
        }
    }
}
