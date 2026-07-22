package com.lyricsync.app.lyrics.model;

import android.graphics.Bitmap;

import java.util.Locale;

public class TrackInfo {
    public String title;
    public String artist;
    public String album;
    public String albumArtUri;
    public Bitmap albumArtBitmap;
    public long duration;
    public String packageName;
    public String trackId;

    public TrackInfo() {}

    public boolean isValid() {
        return title != null && !title.trim().isEmpty()
                && artist != null && !artist.trim().isEmpty();
    }

    public String stableKey() {
        String pkg = normalize(packageName);
        String id = normalize(trackId);
        if (!pkg.isEmpty() && !id.isEmpty()) return pkg + "|||id|||" + id;
        long durationSeconds = duration > 0 ? Math.round(duration / 1000d) : 0;
        return pkg + "|||meta|||" + normalize(title) + "|||" + normalize(artist)
                + "|||" + normalize(album) + "|||" + durationSeconds;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    @Override
    public String toString() {
        return artist + " - " + title;
    }
}
