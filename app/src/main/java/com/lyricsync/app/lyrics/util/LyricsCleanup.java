package com.lyricsync.app.lyrics.util;

import com.lyricsync.app.lyrics.model.LyricsData;

import java.util.Iterator;
import java.util.regex.Pattern;

/**
 * Final pass over every provider result: drops credit lines ("作词 : …",
 * "Composer: …") and the "Title - Artist" header Kugou/Netease put on line 1,
 * and the ad lines Kugou injects, then rebuilds the interlude markers once. Pure JVM.
 */
public final class LyricsCleanup {

    private static final Pattern CREDIT = Pattern.compile(
            "^\\s*(作词|作詞|作曲|编曲|編曲|制作人|製作人|制作|監製|监制|混音|母带|母帶|录音|錄音|和声|和聲|和音|吉他|贝斯|貝斯|鼓|"
                    + "原唱|翻唱|翻译|翻譯|演唱|歌手|词曲|詞曲|"
                    + "弦乐|弦樂|出品|发行|發行|统筹|統籌|OP|SP|词|詞|曲|"
                    + "lyricist|lyrics by|written by|composer|composed by|producer|produced by|arranger|arranged by|"
                    + "mixing|mixed by|mastering|recording|vocal producer|vocal produced|vocal directed|remixed by|original)\\s*[:：]",
            Pattern.CASE_INSENSITIVE);
    /** Kugou ad lines on cover uploads ("本歌曲翻唱由…提供"). */
    private static final Pattern AD = Pattern.compile("翻唱|未音|酷狗|QQ音乐|网易云|纯音乐，请欣赏|純音樂，請欣賞");

    /**
     * Looser rule for the opening block only, where providers put their credits:
     * "Name：", "Wriiten by：…", "David Paich：" — a fullwidth colon, "by:", or a trailing colon.
     */
    private static final Pattern LEADING_CREDIT = Pattern.compile(
            "：|(?i)\\bby\\s*:|[:：]\\s*$");
    /** From the first bracket to the end: "光年之外 (《太空旅客（Passengers）》主题曲)" → "光年之外". */
    private static final Pattern BRACKETED = Pattern.compile("\\s*[(（\\[【《「『].*$");

    private static final int HEADER_SCAN = 3;
    /** Credits sit in the first few lines; deeper than this a colon is lyrics. */
    private static final int CREDIT_SCAN = 25;

    private LyricsCleanup() {}

    /** Cleans {@code lyrics} in place for the track {@code title}; returns it for chaining. */
    public static LyricsData clean(LyricsData lyrics, String title) {
        if (lyrics == null || lyrics.lines == null) return lyrics;
        int seen = 0, index = 0;
        for (Iterator<LyricsData.LyricsLine> it = lyrics.lines.iterator(); it.hasNext(); index++) {
            LyricsData.LyricsLine l = it.next();
            if (l.isInterlude) { it.remove(); continue; }
            String t = l.text == null ? "" : l.text.trim();
            boolean header = seen < HEADER_SCAN && isHeader(t, title);
            // The opening block lasts until the first real lyric line.
            boolean leadingCredit = seen == 0 && index < CREDIT_SCAN && LEADING_CREDIT.matcher(t).find();
            if (t.isEmpty() || header || leadingCredit || CREDIT.matcher(t).find() || AD.matcher(t).find()) {
                it.remove();
                continue;
            }
            seen++;
        }
        LyricsData.insertInterludes(lyrics, 3000);
        return lyrics;
    }

    /** "Heat Waves - Glass Animals", "周杰伦 - 晴天": a line naming the song itself. */
    static boolean isHeader(String line, String title) {
        if (title == null || title.trim().isEmpty() || !line.contains(" - ")) return false;
        String bare = BRACKETED.matcher(title).replaceAll(" ").trim();
        for (String part : line.split("\\s+-\\s+")) {
            String p = BRACKETED.matcher(part).replaceAll(" ").trim();
            if (TrackMatcher.titleSimilarity(title, part) >= 0.9
                    || (!p.isEmpty() && !bare.isEmpty() && TrackMatcher.titleSimilarity(bare, p) >= 0.9)) return true;
        }
        return false;
    }
}
