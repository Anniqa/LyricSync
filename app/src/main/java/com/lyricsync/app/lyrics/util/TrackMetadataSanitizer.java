package com.lyricsync.app.lyrics.util;

import com.lyricsync.app.lyrics.model.TrackInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intelligent metadata sanitizer for audio/video tracks (especially YouTube Music).
 * Cleans noisy titles, removes artist prefixes, extracts dual-script aliases,
 * and strips topic/vevo/feat noise from artists.
 */
public class TrackMetadataSanitizer {

    public static class SanitizedMetadata {
        public final String cleanTitle;
        public final String cleanArtist;
        public final List<String> titleAliases;

        public SanitizedMetadata(String cleanTitle, String cleanArtist, List<String> titleAliases) {
            this.cleanTitle = cleanTitle != null ? cleanTitle : "";
            this.cleanArtist = cleanArtist != null ? cleanArtist : "";
            this.titleAliases = titleAliases != null ? titleAliases : Collections.emptyList();
        }
    }

    private static final Pattern NOISE_BRACKETS = Pattern.compile(
            "(?i)\\s*\\[[^\\]]*(official|video|audio|lyric|lyrics|mv|hd|4k|remaster|remastered|deluxe|visualizer|live|performance)[^\\]]*\\]"
    );

    private static final Pattern NOISE_PARENTHESES = Pattern.compile(
            "(?i)\\s*\\([^)]*(official|video|audio|lyric|lyrics|mv|hd|4k|remaster|remastered|deluxe|visualizer|live|performance|acoustic|instrumental)[^)]*\\)"
    );

    private static final Pattern NOISE_FEAT = Pattern.compile(
            "(?i)\\s*\\((feat\\.?|ft\\.?|with)\\s+[^)]*\\)"
    );

    private static final Pattern NOISE_TRAILING = Pattern.compile(
            "(?i)\\s*-\\s*(official|video|audio|lyric|lyrics|mv|hd|4k|remaster|remastered|visualizer|live|performance|instrumental|acoustic).*$"
    );
    private static final Pattern ARTIST_TITLE_DELIMITER = Pattern.compile("\\s+[-—–]\\s+");

    private static final Pattern ARTIST_NOISE_SUFFIX = Pattern.compile(
            "(?i)\\s*-\\s*topic\\s*$|\\s*vevo\\s*$|\\s*official\\s*$"
    );

    private static final Pattern ARTIST_FEAT = Pattern.compile(
            "(?i)\\s+(feat\\.?|ft\\.?)\\s+.*$"
    );

    private static final Pattern DUAL_SCRIPT_PAREN = Pattern.compile(
            "^(.*?)\\s*\\(([^)]+)\\)\\s*$"
    );

    public static SanitizedMetadata sanitize(TrackInfo track) {
        if (track == null) {
            return new SanitizedMetadata("", "", Collections.emptyList());
        }
        return sanitize(track.title, track.artist);
    }

    public static SanitizedMetadata sanitize(String rawTitle, String rawArtist) {
        String title = rawTitle != null ? rawTitle.trim() : "";
        String artist = rawArtist != null ? rawArtist.trim() : "";

        // 1. Clean artist noise suffix first
        artist = ARTIST_NOISE_SUFFIX.matcher(artist).replaceAll("");
        artist = ARTIST_FEAT.matcher(artist).replaceAll("");
        // Primary artist from comma / ampersand / slash if multiple
        artist = artist.split("[,&/]", 2)[0].trim();

        // 2. Remove noise tags from title
        title = NOISE_FEAT.matcher(title).replaceAll("");
        title = NOISE_BRACKETS.matcher(title).replaceAll("");
        title = NOISE_PARENTHESES.matcher(title).replaceAll("");
        title = NOISE_TRAILING.matcher(title).replaceAll("");
        title = title.replaceAll("\\s+", " ").trim();

        // 3. Artist separation from title if formatted as "Artist - Title"
        String[] parts = ARTIST_TITLE_DELIMITER.split(title, 2);
        if (parts.length == 2) {
            String prefix = parts[0].trim();
            String suffix = parts[1].trim();

            if (!prefix.isEmpty() && !suffix.isEmpty()) {
                if (artist.isEmpty() || isGenericArtist(artist)) {
                    artist = prefix;
                    title = suffix;
                } else if (prefix.equalsIgnoreCase(artist) || artist.toLowerCase().startsWith(prefix.toLowerCase()) || prefix.toLowerCase().startsWith(artist.toLowerCase())) {
                    title = suffix;
                }
            }
        }

        // 4. Dual-script title extraction (e.g. "アイドル (Idol)" or "うっせぇわ - Usseewa")
        List<String> aliases = new ArrayList<>();
        Matcher dualMatcher = DUAL_SCRIPT_PAREN.matcher(title);
        if (dualMatcher.matches()) {
            String primary = dualMatcher.group(1).trim();
            String secondary = dualMatcher.group(2).trim();
            if (!primary.isEmpty() && !secondary.isEmpty()) {
                title = primary;
                aliases.add(secondary);
            }
        } else if (title.contains(" - ") || title.contains(" — ") || title.contains(" – ")) {
            String[] hyphenParts = ARTIST_TITLE_DELIMITER.split(title, 2);
            if (hyphenParts.length == 2) {
                String p1 = hyphenParts[0].trim();
                String p2 = hyphenParts[1].trim();
                if (!p1.isEmpty() && !p2.isEmpty()) {
                    boolean p1NonLatin = com.lyricsync.app.ai.transliteration.ScriptDetector.isNonLatin(p1);
                    boolean p2NonLatin = com.lyricsync.app.ai.transliteration.ScriptDetector.isNonLatin(p2);
                    if (p1NonLatin != p2NonLatin) {
                        title = p1;
                        aliases.add(p2);
                    }
                }
            }
        }
        return new SanitizedMetadata(title, artist, aliases);
    }


    private static boolean isGenericArtist(String artist) {
        if (artist == null || artist.isEmpty()) return true;
        String lower = artist.toLowerCase();
        return lower.contains("topic") || lower.contains("channel") || lower.contains("official")
                || lower.equals("various artists") || lower.equals("unknown");
    }
}
