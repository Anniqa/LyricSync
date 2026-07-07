package com.lyricsync.app.lyrics.cache;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.google.gson.Gson;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;

public class LyricsCacheManager {
    private static final String TAG = "LyricsCache";
    private static final String DB_NAME = "lyrics_cache.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "lyrics";
    private static final long CACHE_DURATION_MS = 7 * 24 * 60 * 60 * 1000L; // 7 days

    private final CacheDBHelper dbHelper;
    private final Gson gson = new Gson();

    public LyricsCacheManager(Context context) {
        dbHelper = new CacheDBHelper(context);
    }

    public LyricsData getCached(TrackInfo track) {
        String key = makeKey(track);
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        try (Cursor cursor = db.query(TABLE,
                new String[]{"json_data", "type", "timestamp"},
                "cache_key = ?",
                new String[]{key},
                null, null, null)) {

            if (cursor != null && cursor.moveToFirst()) {
                long timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"));
                if (System.currentTimeMillis() - timestamp > CACHE_DURATION_MS) {
                    db.delete(TABLE, "cache_key = ?", new String[]{key});
                    return null;
                }

                String json = cursor.getString(cursor.getColumnIndexOrThrow("json_data"));
                String type = cursor.getString(cursor.getColumnIndexOrThrow("type"));

                LyricsData lyrics = gson.fromJson(json, LyricsData.class);
                if (lyrics != null) {
                    lyrics.type = LyricsData.Type.valueOf(type);
                }
                return lyrics;
            }
        } catch (Exception e) {
            Log.e(TAG, "Cache read error", e);
        }

        return null;
    }

    public void cache(TrackInfo track, LyricsData lyrics) {
        String key = makeKey(track);
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put("cache_key", key);
        values.put("title", track.title);
        values.put("artist", track.artist);
        values.put("type", lyrics.type.name());
        values.put("json_data", gson.toJson(lyrics));
        values.put("timestamp", System.currentTimeMillis());

        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void clearAll() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(TABLE, null, null);
    }

    private String makeKey(TrackInfo track) {
        return (track.title + "|" + track.artist).toLowerCase().trim();
    }

    private static class CacheDBHelper extends SQLiteOpenHelper {
        CacheDBHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " ("
                    + "cache_key TEXT PRIMARY KEY, "
                    + "title TEXT, "
                    + "artist TEXT, "
                    + "type TEXT, "
                    + "json_data TEXT, "
                    + "timestamp INTEGER)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE);
            onCreate(db);
        }
    }
}
