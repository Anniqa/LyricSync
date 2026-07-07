package com.lyricsync.app.lyrics.model;

import android.graphics.Bitmap;

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

    public TrackInfo(String title, String artist, String packageName) {
        this.title = title;
        this.artist = artist;
        this.packageName = packageName;
    }

    public boolean isValid() {
        return title != null && !title.isEmpty() && artist != null && !artist.isEmpty();
    }

    @Override
    public String toString() {
        return artist + " - " + title;
    }
}
