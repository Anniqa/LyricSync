package com.lyricsync.app.lyrics.provider;

import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.lyrics.util.TrackQuery;

import org.junit.Assume;
import org.junit.Test;

/** Live (LYRICSYNC_LIVE=1): what Better Lyrics and LyricsPlus return on their own. Prints, never fails. */
public class NewProvidersLiveTest {

    private static final String[][] TRACKS = {
            {"Blinding Lights", "The Weeknd", "After Hours", "200"},
            {"BIRDS OF A FEATHER", "Billie Eilish", "HIT ME HARD AND SOFT", "210"},
            {"Tusa", "KAROL G, Nicki Minaj", "", "200"},
            {"Shape Of You (Lyrics)", "Unknown artist", "", "234"},
            {"米津玄師 MV「KICK BACK」", "米津玄師", "", "194"},
            {"Hati-Hati di Jalan", "Tulus", "Manusia", "242"},
            {"사건의 지평선", "윤하", "END THEORY : Final Edition", "301"},
            {"Lemon", "米津玄師", "Lemon", "255"},
            {"アイドル", "YOASOBI", "アイドル", "213"},
            {"Sial", "Mahalini", "Fabula", "243"},
            {"Espresso", "Sabrina Carpenter", "Short n' Sweet", "175"},
            {"APT.", "ROSÉ", "rosie", "170"},
            {"This Is How I Disappear", "My Chemical Romance", "The Black Parade", "239"},
    };

    @Test
    public void report() {
        Assume.assumeTrue("1".equals(System.getenv("LYRICSYNC_LIVE")));
        LyricsProvider[] ps = {new BetterLyricsProvider(), new LyricsPlusProvider()};
        for (String[] t : TRACKS) {
            com.lyricsync.app.lyrics.model.TrackInfo info = new com.lyricsync.app.lyrics.model.TrackInfo();
            info.title = t[0];
            info.artist = t[1];
            info.album = t[2];
            info.duration = Long.parseLong(t[3]) * 1000;
            TrackQuery q = TrackQuery.from(info);
            StringBuilder row = new StringBuilder(t[1] + " - " + t[0] + ":");
            for (LyricsProvider p : ps) {
                long t0 = System.currentTimeMillis();
                String r;
                try {
                    LyricsData l = p.fetchLyrics(q);
                    r = l == null ? "none" : l.provider + " " + l.type + " " + l.lines.size();
                } catch (Exception e) {
                    r = "ERR " + e.getMessage();
                }
                row.append("  [").append(p.getName()).append(": ").append(r).append(", ")
                        .append(System.currentTimeMillis() - t0).append("ms]");
            }
            System.out.println("LIVE " + row);
        }
    }
}
