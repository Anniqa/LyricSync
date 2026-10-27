package com.lyricsync.app.lyrics.provider;

import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.util.TrackQuery;

/**
 * One lyrics source. Receives the already-cleaned query; returns the best lyrics it
 * has for it, null when it has none, or throws IOException on a network failure.
 */
public interface LyricsProvider {
    String getName();

    LyricsData fetchLyrics(TrackQuery query) throws Exception;
}
