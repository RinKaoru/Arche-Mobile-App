package app.arche.nas;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.HashMap;
import java.util.Map;

/** Mémoire locale des photos déjà sauvegardées (id MediaStore + taille + date de modification). */
public final class Done extends SQLiteOpenHelper {
    private static Done inst;
    public static synchronized Done get(Context c) { if (inst == null) inst = new Done(c.getApplicationContext()); return inst; }
    private Done(Context c) { super(c, "done.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE done(id INTEGER PRIMARY KEY, size INTEGER NOT NULL, mod INTEGER NOT NULL, ts INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {}

    /** id → "taille:modif" */
    public Map<Long, String> all() {
        Map<Long, String> m = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, size, mod FROM done", null)) {
            while (c.moveToNext()) m.put(c.getLong(0), c.getLong(1) + ":" + c.getLong(2));
        }
        return m;
    }

    public void mark(Media.Item it) {
        ContentValues v = new ContentValues();
        v.put("id", it.id); v.put("size", it.size); v.put("mod", it.modified); v.put("ts", System.currentTimeMillis() / 1000);
        getWritableDatabase().insertWithOnConflict("done", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM done", null)) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }

    public void clear() { getWritableDatabase().delete("done", null, null); }

    static String key(Media.Item it) { return it.size + ":" + it.modified; }
}
