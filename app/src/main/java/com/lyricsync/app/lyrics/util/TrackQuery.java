package com.lyricsync.app.lyrics.util;

import com.lyricsync.app.ai.transliteration.ScriptDetector;
import com.lyricsync.app.lyrics.model.TrackInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What to search for, derived once from the player's (often noisy) metadata.
 * Every provider reads this instead of cleaning the title itself. Pure JVM.
 *
 * <pre>
 * "Ed Sheeran - Perfect [Official Lyric Video]" / ""            -> Ed Sheeran · Perfect
 * "YOASOBI「アイドル」Official Music Video" / "Ayase / YOASOBI"   -> YOASOBI · アイドル (+ "aidoru")
 * "Bohemian Rhapsody - Remastered 2011" / "Queen"               -> Queen · Bohemian Rhapsody
 * "Heat Waves (Sped Up)" / "Glass Animals"                      -> Glass Animals · Heat Waves (Sped Up) (+ "Heat Waves")
 * </pre>
 * Words that change the recording (live, remix, sped up …) stay in the title so the matching
 * version wins; the bracket-free title is always offered as an alias.
 */
public final class TrackQuery {

    /** Cleaned title, as specific as the player gave it. */
    public final String title;
    /** Primary artist, "" when unknown. */
    public final String artist;
    /** Every credited artist ("Dua Lipa, DaBaby"), for matching only. */
    public final String artists;
    /** Other spellings to try when {@link #title} finds nothing: bare title, other script, romaji. */
    public final List<String> aliases;
    public final long durationMs;
    /** 22-char Spotify id when the player exposed one, else null. */
    public final String spotifyId;
    /** The title carried video noise ("Official Video", "MV" …): its length is the video's, not the song's. */
    public final boolean fromVideo;

    private TrackQuery(String title, String artist, String artists, List<String> aliases,
                       long durationMs, String spotifyId, boolean fromVideo) {
        this.title = title;
        this.artist = artist;
        this.artists = artists;
        this.aliases = Collections.unmodifiableList(aliases);
        this.durationMs = Math.max(0, durationMs);
        this.spotifyId = spotifyId;
        this.fromVideo = fromVideo;
    }

    public static TrackQuery from(TrackInfo t) {
        if (t == null) return of("", "", 0);
        TrackQuery q = of(t.title, t.artist, t.duration);
        String id = spotifyId(t.trackId);
        return id == null ? q : new TrackQuery(q.title, q.artist, q.artists, q.aliases, q.durationMs, id, q.fromVideo);
    }

    public static TrackQuery of(String rawTitle, String rawArtist, long durationMs) {
        String artistField = cleanArtist(rawArtist);
        String title = LEADING_TRACK_NO.matcher(trim(rawTitle)).replaceFirst("");
        boolean fromVideo = VIDEO.matcher(title).find();
        String titleArtist = null; // artist named inside the title (YouTube style)
        Set<String> aliases = new LinkedHashSet<>();

        // 「Title」『Title』'Title': the quoted part is the song, what precedes it the artist.
        Matcher quoted = QUOTED.matcher(title);
        if (quoted.find()) {
            String song = firstNonNull(quoted.group(1), quoted.group(2), quoted.group(3));
            String before = stripNoise(title.substring(0, quoted.start()));
            title = song;
            if (!before.isEmpty()) titleArtist = before;
        }
        title = stripNoise(title);

        // "Artist - Title", "Title - Artist", "Title - Remastered 2011", "うっせぇわ - Usseewa".
        String[] dash = DASH.split(title, 2);
        if (dash.length == 2 && !dash[0].trim().isEmpty() && !dash[1].trim().isEmpty()) {
            String left = stripFeat(dash[0]), right = stripFeat(dash[1]);
            if (isArtist(left, artistField)) {
                titleArtist = left;        // keeps the spaced spelling of "ImagineDragonsVEVO"
                title = right;
            } else if (isArtist(right, artistField)) {
                title = left;
            } else if (VERSION_HEAD.matcher(right).find() || VERSION_END.matcher(right).find()) {
                title = RECORDING_VERSION.matcher(right).find() ? left + " (" + right + ")" : left;
            } else if (ScriptDetector.isNonLatin(left) != ScriptDetector.isNonLatin(right)) {
                title = left;              // native script first, Latin spelling as alias
                aliases.add(right);
            } else {
                titleArtist = left;        // channel upload: "Bruno Mars - Talking To The Moon" by 7clouds
                title = right;
                aliases.add(left + " - " + right);
            }
        }
        title = stripFeat(title);

        // The artist named in the title leads (the field is often a channel or a co-writer).
        String artists = artistField;
        String named = titleArtist == null ? "" : cleanArtist(bracketFree(titleArtist));
        if (!named.isEmpty()) {
            artists = artistField.isEmpty() || isArtist(named, artistField) ? named : named + ", " + artistField;
        }
        String artist = ARTIST_SPLIT.split(artists, 2)[0].trim();

        // "アイドル (Idol)" -> アイドル + alias Idol; "KAIKAI KITAN (廻廻奇譚)" -> both kept.
        Matcher dual = TRAILING_BRACKET.matcher(title);
        if (dual.matches()) {
            String outer = dual.group(1).trim(), inner = dual.group(2).trim();
            if (!outer.isEmpty() && !inner.isEmpty()
                    && ScriptDetector.isNonLatin(outer) != ScriptDetector.isNonLatin(inner)) {
                title = outer;
                aliases.add(inner);
            }
        }
        String bare = bracketFree(title);
        if (!bare.equalsIgnoreCase(title)) aliases.add(bare);
        Matcher tag = LEADING_TAG.matcher(title);
        if (tag.find()) aliases.add(title.substring(tag.end()).trim());
        if (W_SHORTHAND.matcher(title).find()) aliases.add(W_SHORTHAND.matcher(title).replaceAll(" with "));
        if (JapaneseRomanizer.containsKana(title) && !HAN.matcher(title).find()) {
            aliases.add(JapaneseRomanizer.romanize(title).trim()); // kana only: kanji have no reading here
        }

        List<String> clean = new ArrayList<>();
        for (String a : aliases) {
            if (!a.isEmpty() && !a.equalsIgnoreCase(title) && !clean.contains(a)) clean.add(a);
        }
        return new TrackQuery(title, artist, artists, clean, durationMs, null, fromVideo);
    }

    /** Title first, then the aliases: the spellings a provider should search, in order. */
    public List<String> titles() {
        List<String> all = new ArrayList<>(aliases.size() + 1);
        all.add(title);
        all.addAll(aliases);
        return all;
    }

    /** Match score of a search candidate against this track (see {@link TrackMatcher}). */
    public double score(String candTitle, String candArtist, long candDurationMs) {
        // A video's length (intro, skits, outro) says little about the song's.
        long d = fromVideo && candDurationMs > 0 && candDurationMs < durationMs ? 0 : durationMs;
        return TrackMatcher.score(title, aliases, artists, d, candTitle, candArtist, candDurationMs);
    }

    public boolean hasArtist() {
        return !artist.isEmpty();
    }

    public boolean isEmpty() {
        return title.isEmpty();
    }

    @Override
    public String toString() {
        return (artist.isEmpty() ? "?" : artist) + " - " + title + (aliases.isEmpty() ? "" : " " + aliases);
    }

    // ── cleaning ────────────────────────────────────────────────────────

    private static final String NOISE =
            "official|video|audio|lyrics?|visuali[sz]er|mv|m/v|music video|hd|hq|4k|remaster(?:ed)?|explicit"
                    + "|full album|color coded|letra|legendado|vietsub|clean version";
    private static final Pattern NOISE_BRACKET = Pattern.compile(
            "(?i)\\s*[(\\[【][^)\\]】]*\\b(?:" + NOISE + ")\\b[^)\\]】]*[)\\]】]");
    private static final Pattern NOISE_TAIL = Pattern.compile(
            "(?i)\\s+(?:official\\s+(?:music\\s+|lyric\\s+)?(?:video|audio|mv)|mv|m/v|lyrics?(?:\\s+video)?|full\\s+album)\\s*$");
    private static final Pattern VIDEO = Pattern.compile("(?i)\\b(?:video|mv|m/v|visuali[sz]er|lyrics?)\\b|「|『");
    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");
    private static final Pattern FEAT_BRACKET = Pattern.compile(
            "(?i)\\s*[(\\[](?:feat\\.?|ft\\.?|featuring|with)\\s[^)\\]]*[)\\]]");
    private static final Pattern FEAT_TAIL = Pattern.compile("(?i)\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$");
    private static final Pattern DASH = Pattern.compile("\\s+[-–—]\\s+");
    /** A "Title - …" suffix naming a release/version, not an artist: "2013 Remaster", "Live at …". */
    private static final Pattern VERSION_HEAD = Pattern.compile(
            "(?i)^(?:(?:19|20)\\d{2}\\s+)?(?:live|remaster(?:ed)?|version|ver\\.?|edit|mix|remix|acoustic|demo|"
                    + "mono|stereo|bonus|instrumental|session|edition|sped|slowed|single|radio|from|recorded)\\b");
    /** … or ending in a version word: "Henry Land Remix", "Radio Edit", "Deluxe Version". */
    private static final Pattern VERSION_END = Pattern.compile(
            "(?i)\\b(?:remaster(?:ed)?|version|edit|mix|remix|live)$");
    /** …of which these name a different recording, so they stay part of the title. */
    private static final Pattern RECORDING_VERSION = Pattern.compile(
            "(?i)\\b(?:live|mix|remix|acoustic|demo|instrumental|sped|slowed|session)\\b");
    private static final Pattern QUOTED = Pattern.compile("「([^」]+)」|『([^』]+)』|(?:^|\\s)'([^']+)'(?=\\s|$)");
    private static final Pattern LEADING_TRACK_NO = Pattern.compile("^\\d{1,3}\\s*[.)]\\s+|^\\d{1,3}\\s+-\\s+");
    private static final Pattern LEADING_TAG = Pattern.compile("^\\[[^\\]]{1,12}]\\s+");
    private static final Pattern TRAILING_BRACKET = Pattern.compile("^(.*?)\\s*[(（]([^)）]+)[)）]\\s*$");
    private static final Pattern ANY_BRACKET = Pattern.compile("\\s*[(\\[（【][^)\\]）】]*[)\\]）】]");
    private static final Pattern W_SHORTHAND = Pattern.compile("(?i)\\s+w[-/]\\s+");
    private static final Pattern ARTIST_NOISE = Pattern.compile(
            "(?i)\\s*-\\s*topic$|vevo$|\\s+official$|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$");
    private static final Pattern ARTIST_SPLIT = Pattern.compile("\\s*(?:,|&|/|;|、|＆|\\s[xX×]\\s)\\s*");
    private static final Set<String> UNKNOWN_ARTISTS = new HashSet<>(Arrays.asList(
            "", "unknown", "unknown artist", "<unknown>", "various artists", "various", "va", "artist"));
    private static final Pattern SPOTIFY_ID =
            Pattern.compile("(?:spotify:track:|.*open\\.spotify\\.com/track/)?([A-Za-z0-9]{22})(?:\\?.*)?");

    private static String stripNoise(String s) {
        String out = NOISE_BRACKET.matcher(s).replaceAll("");
        out = NOISE_TAIL.matcher(out).replaceAll("");
        return out.replaceAll("\\s+", " ").trim();
    }

    private static String stripFeat(String s) {
        String out = FEAT_BRACKET.matcher(s).replaceAll("");
        return FEAT_TAIL.matcher(out).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    private static String bracketFree(String s) {
        String b = ANY_BRACKET.matcher(s).replaceAll("").replaceAll("\\s+", " ").trim();
        return b.isEmpty() ? s.trim() : b;
    }

    private static String cleanArtist(String raw) {
        String a = ARTIST_NOISE.matcher(trim(raw)).replaceAll("").trim();
        return UNKNOWN_ARTISTS.contains(a.toLowerCase(Locale.ROOT)) ? "" : a;
    }

    private static boolean isArtist(String part, String artistField) {
        return !artistField.isEmpty() && TrackMatcher.artistSimilarity(part, artistField) >= 0.8;
    }

    private static String spotifyId(String trackId) {
        if (trackId == null) return null;
        Matcher m = SPOTIFY_ID.matcher(trackId.trim());
        return m.matches() ? m.group(1) : null;
    }

    private static String firstNonNull(String... xs) {
        for (String x : xs) if (x != null) return x;
        return "";
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
