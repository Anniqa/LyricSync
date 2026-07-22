package com.lyricsync.app.lyrics.provider;

import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.model.TrackInfo;

public interface LyricsProvider {
    String getName();
    LyricsData fetchLyrics(TrackInfo track) throws Exception;
}
