package app.arche.nas;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Lecture des albums et des photos/vidéos du téléphone (MediaStore). */
public final class Media {

    public static final class Album {
        public String id, name, path = "";
        public int count;
        public long coverId; public boolean coverVideo;
    }

    public static final class Item {
        public long id, size, taken, modified, added;
        public String name, album, mime;
        public boolean video;
        public Uri uri() {
            return ContentUris.withAppendedId(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
        }
    }

    private static Uri filesUri() { return MediaStore.Files.getContentUri("external"); }

    private static String typeSelection(boolean videos) {
        return MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                + (videos ? " OR " + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO : "");
    }

    /** Tous les albums du téléphone, les plus remplis d'abord. */
    public static List<Album> albums(Context c, boolean videos) {
        Map<String, Album> map = new LinkedHashMap<>();
        String[] proj = Build.VERSION.SDK_INT >= 29
                ? new String[]{"bucket_id", "bucket_display_name", MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE}
                : new String[]{"bucket_id", "bucket_display_name", MediaStore.MediaColumns.DATA, MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE};
        try (Cursor cur = c.getContentResolver().query(filesUri(), proj, "(" + typeSelection(videos) + ")", null,
                MediaStore.Files.FileColumns.DATE_ADDED + " DESC")) {
            if (cur == null) return new ArrayList<>();
            while (cur.moveToNext()) {
                String id = cur.getString(0);
                if (id == null) continue;
                Album a = map.get(id);
                if (a == null) {
                    a = new Album(); a.id = id; a.name = cur.getString(1) == null ? "Sans nom" : cur.getString(1);
                    String p = cur.getString(2);
                    if (p != null && Build.VERSION.SDK_INT < 29) { int i = p.lastIndexOf('/'); p = i > 0 ? p.substring(0, i) : p; p = p.replaceFirst("^/storage/emulated/0/?", ""); }
                    a.path = p == null ? "" : p.replaceAll("/+$", "");
                    a.coverId = cur.getLong(3);
                    a.coverVideo = cur.getInt(4) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                    map.put(id, a);
                }
                a.count++;
            }
        }
        List<Album> out = new ArrayList<>(map.values());
        out.sort((x, y) -> {
            int dx = isDefault(x) ? 0 : 1, dy = isDefault(y) ? 0 : 1;
            return dx != dy ? dx - dy : y.count - x.count;
        });
        return out;
    }

    /** Choix par défaut : appareil photo, captures d'écran, images WhatsApp. */
    public static boolean isDefault(Album a) {
        String n = (a.name + " " + a.path).toLowerCase(Locale.ROOT);
        return n.contains("dcim/camera") || n.equals("camera dcim/camera") || a.name.equalsIgnoreCase("Camera")
                || n.contains("screenshot") || n.contains("capture") || n.contains("whatsapp images") || n.contains("whatsapp video");
    }

    public static Set<String> chosenAlbums(Context c, Prefs prefs) {
        Set<String> s = prefs.albums();
        if (s != null) return s;
        Set<String> d = new HashSet<>();
        for (Album a : albums(c, true)) if (isDefault(a)) d.add(a.id);
        return d;
    }

    /** Photos/vidéos des albums choisis, les plus récentes d'abord. */
    public static List<Item> items(Context c, Set<String> buckets, boolean videos) {
        List<Item> out = new ArrayList<>();
        if (buckets.isEmpty()) return out;
        StringBuilder in = new StringBuilder();
        String[] args = buckets.toArray(new String[0]);
        for (int i = 0; i < args.length; i++) in.append(i == 0 ? "?" : ",?");
        String[] proj = {MediaStore.Files.FileColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                "datetaken", MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_ADDED, "bucket_display_name",
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.Files.FileColumns.MEDIA_TYPE};
        String sel = "(" + typeSelection(videos) + ") AND bucket_id IN (" + in + ")";
        if (Build.VERSION.SDK_INT >= 29) sel += " AND " + MediaStore.MediaColumns.IS_PENDING + "=0";
        try (Cursor cur = c.getContentResolver().query(filesUri(), proj, sel, args, MediaStore.Files.FileColumns.DATE_ADDED + " DESC")) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                Item it = new Item();
                it.id = cur.getLong(0); it.name = cur.getString(1); it.size = cur.getLong(2);
                it.taken = cur.isNull(3) ? 0 : cur.getLong(3); it.modified = cur.getLong(4); it.added = cur.getLong(5);
                it.album = cur.getString(6); it.mime = cur.getString(7);
                it.video = cur.getInt(8) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                if (it.name == null || it.size <= 0) continue;
                out.add(it);
            }
        }
        return out;
    }

    /** Petite vignette en data: URL, pour l'écran de choix des albums dans Arche. */
    public static String thumb(Context c, Album a) {
        try {
            Uri u = ContentUris.withAppendedId(a.coverVideo ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, a.coverId);
            Bitmap b;
            if (Build.VERSION.SDK_INT >= 29) b = c.getContentResolver().loadThumbnail(u, new Size(144, 144), null);
            else b = MediaStore.Images.Thumbnails.getThumbnail(c.getContentResolver(), a.coverId, MediaStore.Images.Thumbnails.MINI_KIND, null);
            if (b == null) return null;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 72, o);
            return "data:image/jpeg;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { return null; }
    }

    /** Ouvre le fichier original (avec la position GPS si l'autorisation est donnée). */
    public static Uri original(Context c, Item it) {
        Uri u = it.uri();
        if (Build.VERSION.SDK_INT >= 29 && Perms.location(c)) {
            try { u = MediaStore.setRequireOriginal(u); } catch (Exception ignored) {}
        }
        return u;
    }

    static ContentResolver cr(Context c) { return c.getContentResolver(); }
}
