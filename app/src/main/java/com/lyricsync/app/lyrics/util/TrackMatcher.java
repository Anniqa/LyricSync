package com.lyricsync.app.lyrics.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One scoring rule for every lyrics source: how well does a search candidate
 * (title, artist, duration) match the track that is playing? Pure JVM.
 *
 * score = 0.50·title + 0.35·artist + 0.15·duration − 0.25 if the candidate is a
 * different version (remix, live, cover, karaoke …) the player did not ask for.
 * A title below 0.25 or a comparable artist below 0.25 is rejected outright (0).
 */
public final class TrackMatcher {

    /** Minimum score a candidate needs to be used at all. */
    public static final double MIN_SCORE = 0.45;

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern BRACKETED = Pattern.compile("\\s*[(\\[（【「『][^)\\]）】」』]*[)\\]）】」』]");
    private static final Pattern ARTIST_SPLIT = Pattern.compile(
            "\\s*(?:,|&|/|;|、|＆|\\bx\\b|\\bfeat\\.?|\\bft\\.?|\\bwith\\b|\\band\\b)\\s*", Pattern.CASE_INSENSITIVE);

    /** Markers of a different recording/arrangement than the original song. */
    private static final Set<String> VERSION_WORDS = new HashSet<>(java.util.Arrays.asList(
            "remix", "mix", "live", "cover", "karaoke", "instrumental", "inst", "acoustic", "sped",
            "slowed", "nightcore", "reverb", "demo", "dj", "8d", "piano", "orchestra", "guitar", "lofi"));
    private static final String[] VERSION_CJK = {
            "现场", "現場", "翻唱", "伴奏", "纯音乐", "純音樂", "钢琴", "鋼琴", "吉他", "ピアノ", "オルゴール",
            "カラオケ", "ライブ", "라이브", "커버"};

    private TrackMatcher() {}

    public static double score(String qTitle, List<String> aliases, String qArtist, long qDurationMs,
                               String cTitle, String cArtist, long cDurationMs) {
        double ts = titleSimilarity(qTitle, cTitle);
        if (aliases != null) {
            for (String a : aliases) ts = Math.max(ts, titleSimilarity(a, cTitle));
        }
        if (ts < 0.25) return 0;

        double as = 0.5; // unknown: neutral
        if (!blank(qArtist) && !blank(cArtist)) {
            double sim = artistSimilarity(qArtist, cArtist);
            if (sim >= 0.25) as = sim;
            else if (sameScriptFamily(qArtist, cArtist)) return 0;
            // else: "Kenshi Yonezu" vs "米津玄師" cannot be compared; keep it neutral
        }

        double ds = 0.5;
        if (qDurationMs > 0 && cDurationMs > 0) {
            ds = Math.max(0, 1 - Math.abs(qDurationMs - cDurationMs) / 15000.0);
        }
        double s = ts * 0.50 + as * 0.35 + ds * 0.15;
        if (isOtherVersion(qTitle, cTitle)) s -= 0.25;
        return s;
    }

    /** Title similarity in [0,1]; compares both the full titles and their bracket-free bases. */
    public static double titleSimilarity(String a, String b) {
        if (blank(a) || blank(b)) return 0;
        return Math.max(similarity(a, b), similarity(base(a), base(b)));
    }

    /** Artist similarity in [0,1]; multi-artist strings are compared name by name. */
    public static double artistSimilarity(String a, String b) {
        if (blank(a) || blank(b)) return 0;
        double best = similarity(a, b);
        for (String pa : splitArtists(a)) {
            for (String pb : splitArtists(b)) best = Math.max(best, similarity(pa, pb));
        }
        return best;
    }

    /** True when {@code candidate} names a version marker (remix, live …) that {@code query} lacks. */
    public static boolean isOtherVersion(String query, String candidate) {
        if (blank(candidate)) return false;
        Set<String> q = words(query == null ? "" : query);
        Set<String> c = words(candidate);
        for (String w : c) {
            if (VERSION_WORDS.contains(w) && !q.contains(w)) return true;
        }
        String qs = query == null ? "" : query;
        for (String m : VERSION_CJK) {
            if (candidate.contains(m) && !qs.contains(m)) return true;
        }
        return false;
    }

    /** Lowercase, accents folded, width-folded, punctuation collapsed to single spaces. */
    public static String normalize(String s) {
        if (s == null) return "";
        String folded = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("[\\u0300-\\u036f]", "");
        folded = Normalizer.normalize(folded, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace("&", " and ");
        return NON_WORD.matcher(folded).replaceAll(" ").trim();
    }

    // ── internals ───────────────────────────────────────────────────────

    private static double similarity(String a, String b) {
        String na = normalize(a), nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) return 0;
        if (na.equals(nb) || na.replace(" ", "").equals(nb.replace(" ", ""))) return 1;
        double s = tokenOverlap(tokens(na), tokens(nb));
        if (Math.min(na.length(), nb.length()) >= 2 && (na.contains(nb) || nb.contains(na))) s = Math.max(s, 0.75);
        return s;
    }

    private static String base(String s) {
        String b = BRACKETED.matcher(s).replaceAll(" ").trim();
        return b.isEmpty() ? s : b;
    }

    private static List<String> splitArtists(String s) {
        List<String> out = new ArrayList<>();
        for (String p : ARTIST_SPLIT.split(base(s))) {
            if (!p.trim().isEmpty()) out.add(p.trim());
        }
        return out;
    }

    /** recall-weighted overlap: how much of {@code a} is found in {@code b}. */
    private static double tokenOverlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int hits = 0;
        for (String t : a) if (b.contains(t)) hits++;
        return 0.65 * hits / a.size() + 0.35 * hits / b.size();
    }

    /** Words (len > 1, or any when there are only 1–2), CJK chars + bigrams, and the compact string. */
    private static Set<String> tokens(String normalized) {
        Set<String> set = new HashSet<>();
        String[] ws = normalized.split(" ");
        for (String w : ws) {
            if (w.isEmpty()) continue;
            if (w.length() > 1 || ws.length <= 2) set.add(w);
            for (int i = 0; i < w.length(); i++) {
                if (isCjk(w.charAt(i))) {
                    set.add(String.valueOf(w.charAt(i)));
                    if (i + 1 < w.length()) set.add(w.substring(i, i + 2));
                }
            }
        }
        set.add(normalized.replace(" ", ""));
        return set;
    }

    private static Set<String> words(String s) {
        Set<String> set = new HashSet<>();
        for (String w : normalize(s).split(" ")) if (!w.isEmpty()) set.add(w);
        return set;
    }

    private static boolean sameScriptFamily(String a, String b) {
        return hasCjk(a) == hasCjk(b);
    }

    static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) if (isCjk(s.charAt(i))) return true;
        return false;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x3040 && c <= 0x30ff) || (c >= 0x3400 && c <= 0x9fff)
                || (c >= 0xac00 && c <= 0xd7af) || (c >= 0xf900 && c <= 0xfaff);
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
