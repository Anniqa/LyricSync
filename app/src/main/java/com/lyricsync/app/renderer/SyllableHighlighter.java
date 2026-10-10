package com.lyricsync.app.renderer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.lyricsync.app.ai.transliteration.AiSyllableTransliterationService;
import com.lyricsync.app.ai.transliteration.ScriptDetector;
import com.lyricsync.app.lyrics.model.LyricsData;
import com.lyricsync.app.ui.Anim;

import java.util.ArrayList;
import java.util.List;

public class SyllableHighlighter {
    // SpicyLyrics: words are always bold (font-weight: 700). Size is constant;
    // the ScaleSpline (rest 0.95, peak 1.0505) provides the per-word scale.
    // Non-active lines are scaled to --DefaultLineScale (0.95) via CSS `scale`.
    // SpicyLyrics line opacity model: NotSung 0.51, Sung 0.497, Active 1.0
    // (multiplied with the per-word gradient fill of 0.35 dim / 0.85 bright).
    private static final int ACTIVE_COLOR = Color.WHITE;
    // Non-active (upcoming and already-sung-but-inactive) lines share this dim color.
    private static final int INACTIVE_COLOR = 0x82FFFFFF;
    private static final int PAST_COLOR = 0x7FFFFFFF;

    private static final int STATE_UPCOMING = 0;
    private static final int STATE_ACTIVE = 1;
    private static final int STATE_PAST = 2;

    private final Context context;
    private final Typeface fontBold;
    private final Typeface fontMedium;
    private final float fontSizeSp;
    private final List<LineView> lineViews = new ArrayList<>();
    private boolean syllableMode = true;
    private AnimConfig animConfig = AnimConfig.defaultConfig();

    public static class LineView {
        public View rootView;
        public GradientTextView gradientView;
        public InterludeDotView interludeView;
        public WordFlowLayout wordFlowLayout;
        public List<GradientWordView> wordViews;
        public WordFlowLayout romajiWordFlowLayout;
        public List<GradientWordView> romajiWordViews;
        public List<List<GradientWordView>> backgroundWordViews;
        public List<boolean[]> bgWordLastActive;
        public List<View> bgFlowLayouts;
        public LyricsData.LyricsLine line;
        public boolean usePerWord;
        public int lastState = -1;
        public float lastProgress = -1f;
        /** Line-level scale spring: the active line grows a touch, others rest at 1. */
        public Spring lineScale;
        public TextView translationView;
    }

    public static class DisplayWord {
        public String text = "";
        public String romanji = "";
        public long startTime;
        public long endTime;
        public final List<LyricsData.Word> syllables = new ArrayList<>();
    }

    public static List<DisplayWord> buildDisplayWords(LyricsData.LyricsLine line) {
        List<DisplayWord> list = new ArrayList<>();
        if (line == null || line.words == null || line.words.isEmpty()) return list;

        DisplayWord cur = null;
        int n = line.words.size();
        for (int i = 0; i < n; i++) {
            LyricsData.Word w = line.words.get(i);
            if (cur == null) {
                cur = new DisplayWord();
                cur.text = (w.text != null) ? w.text : "";
                cur.romanji = (w.romanji != null) ? ScriptDetector.stripNonLatinCharacters(w.romanji) : "";
                cur.startTime = w.startTime;
                cur.endTime = w.endTime;
                cur.syllables.add(w);
            } else {
                LyricsData.Word prev = line.words.get(i - 1);
                String r = (w.romanji != null) ? ScriptDetector.stripNonLatinCharacters(w.romanji).trim() : "";
                // Safety limit: never merge more than 4 syllables or 16 romaji characters into one DisplayWord
                // to ensure WordFlowLayout can always wrap words and never overflow off-screen.
                boolean canMerge = prev.isPartOfWord
                        && cur.syllables.size() < 4
                        && (cur.romanji.length() + r.length()) <= 16;
                if (canMerge) {
                    cur.text += (w.text != null ? w.text : "");
                    if (!r.isEmpty()) {
                        cur.romanji += r;
                    }
                    cur.endTime = Math.max(cur.endTime, w.endTime);
                    cur.syllables.add(w);
                } else {
                    list.add(cur);
                    cur = new DisplayWord();
                    cur.text = (w.text != null) ? w.text : "";
                    cur.romanji = r;
                    cur.startTime = w.startTime;
                    cur.endTime = w.endTime;
                    cur.syllables.add(w);
                }
            }
        }
        if (cur != null) {
            list.add(cur);
        }
        return list;
    }

    public SyllableHighlighter(Context context, Typeface fontBold, Typeface fontMedium, float fontSizeSp) {
        this.context = context;
        this.fontBold = fontBold;
        this.fontMedium = fontMedium;
        this.fontSizeSp = fontSizeSp;
    }

    public void setSyllableMode(boolean syllable) {
        this.syllableMode = syllable;
    }

    public void setAnimConfig(AnimConfig config) {
        this.animConfig = config;
        for (LineView lv : lineViews) {
            if (lv.wordViews != null) {
                for (GradientWordView wv : lv.wordViews) {
                    wv.setAnimConfig(config);
                }
            }
            if (lv.romajiWordViews != null) {
                for (GradientWordView rwv : lv.romajiWordViews) {
                    rwv.setAnimConfig(config);
                }
            }
            if (lv.backgroundWordViews != null) {
                for (List<GradientWordView> bgList : lv.backgroundWordViews) {
                    for (GradientWordView bwv : bgList) {
                        bwv.setAnimConfig(config);
                    }
                }
            }
        }
    }

    private float getRomajiFontSize() {
        return !originalScriptEnabled ? (fontSizeSp * 0.90f) : (fontSizeSp * 0.70f);
    }

    private boolean transliterationEnabled = true;
    private boolean originalScriptEnabled = true;

    public void setTransliterationEnabled(boolean enabled) {
        this.transliterationEnabled = enabled;
        for (LineView lv : lineViews) {
            updateLineScriptVisibility(lv);
        }
    }

    public boolean isTransliterationEnabled() {
        return transliterationEnabled;
    }

    public void setOriginalScriptEnabled(boolean enabled) {
        this.originalScriptEnabled = enabled;
        for (LineView lv : lineViews) {
            updateLineScriptVisibility(lv);
            updateRomajiLayerStyle(lv);
        }
    }

    public boolean isOriginalScriptEnabled() {
        return originalScriptEnabled;
    }

    private void updateRomajiLayerStyle(LineView lv) {
        if (lv == null || lv.romajiWordFlowLayout == null || lv.romajiWordViews == null) return;
        boolean soloRomaji = !originalScriptEnabled;
        int topPadding = soloRomaji ? dpToPx(4) : dpToPx(1);
        lv.romajiWordFlowLayout.setPadding(0, topPadding, 0, dpToPx(1));

        float romajiFontSize = getRomajiFontSize();
        Typeface tf = soloRomaji ? fontBold : fontMedium;
        int color = lv.lastState == STATE_ACTIVE ? ACTIVE_COLOR : (lv.lastState == STATE_PAST ? PAST_COLOR : INACTIVE_COLOR);

        for (GradientWordView rwv : lv.romajiWordViews) {
            rwv.setWordStyle(romajiFontSize, color, tf);
        }
        lv.romajiWordFlowLayout.requestLayout();
    }

    public void updateLineScriptVisibility(LineView lv) {
        if (lv == null) return;
        boolean hasRomanji = lv.line != null && hasAnyWordRomanji(lv.line);
        boolean hasActiveRomaji = lv.romajiWordFlowLayout != null && transliterationEnabled && hasRomanji;
        boolean showOriginal = originalScriptEnabled || !hasActiveRomaji;
        if (lv.wordFlowLayout != null) {
            lv.wordFlowLayout.setVisibility(showOriginal ? View.VISIBLE : View.GONE);
        }
        if (lv.gradientView != null) {
            lv.gradientView.setVisibility(showOriginal ? View.VISIBLE : View.GONE);
        }
        if (lv.romajiWordFlowLayout != null) {
            lv.romajiWordFlowLayout.setVisibility(transliterationEnabled && hasRomanji ? View.VISIBLE : View.GONE);
        }
    }

    public boolean hasAnyWordRomanji(LyricsData.LyricsLine line) {
        if (line == null || line.words == null) return false;
        for (LyricsData.Word w : line.words) {
            if (w.romanji != null && !w.romanji.trim().isEmpty()) return true;
        }
        return false;
    }

    public List<LineView> getLineViews() {
        return lineViews;
    }

    public void updateLineTransliteration(int index, List<String> wordsRomanji, String lineRomanji) {
        if (index < 0 || index >= lineViews.size()) return;
        LineView lv = lineViews.get(index);
        if (lv.line == null) return;
        lv.line.romanji = lineRomanji != null ? ScriptDetector.stripNonLatinCharacters(lineRomanji) : null;
        if (wordsRomanji != null && lv.line.words != null) {
            for (int i = 0; i < Math.min(wordsRomanji.size(), lv.line.words.size()); i++) {
                String rToken = wordsRomanji.get(i);
                lv.line.words.get(i).romanji = ScriptDetector.stripNonLatinCharacters(rToken);
            }
        }
        AiSyllableTransliterationService.computeWordBoundaries(lv.line);
        boolean hasRomanji = hasAnyWordRomanji(lv.line);
        if (lv.usePerWord && lv.rootView instanceof LinearLayout) {
            LinearLayout container = (LinearLayout) lv.rootView;
            if (hasRomanji) {
                setupRomajiLayer(lv.line, lv, container);
            } else if (lv.romajiWordFlowLayout != null) {
                container.removeView(lv.romajiWordFlowLayout);
                lv.romajiWordFlowLayout = null;
                lv.romajiWordViews = null;
            }
        }
        updateLineScriptVisibility(lv);
        updateRomajiLayerStyle(lv);
    }

    private boolean translationEnabled = false;

    public void setTranslationEnabled(boolean enabled) {
        this.translationEnabled = enabled;
        for (LineView lv : lineViews) {
            if (lv.translationView != null) {
                boolean hasText = lv.line != null && lv.line.translation != null && !lv.line.translation.trim().isEmpty();
                lv.translationView.setVisibility(enabled && hasText ? View.VISIBLE : View.GONE);
            }
        }
    }

    public void updateLineTranslation(int index, String translation) {
        if (index < 0 || index >= lineViews.size()) return;
        LineView lv = lineViews.get(index);
        if (lv.translationView != null) {
            lv.translationView.setText(translation != null ? translation : "");
            boolean hasText = translation != null && !translation.trim().isEmpty();
            lv.translationView.setVisibility(translationEnabled && hasText ? View.VISIBLE : View.GONE);
        }
    }

    private TextView createTranslationTextView(LyricsData.LyricsLine line) {
        if (line.isInterlude) return null;
        TextView tv = new TextView(context);
        float transFontSize = Math.max(9f, fontSizeSp * 0.62f);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, transFontSize);
        tv.setTypeface(fontMedium);
        tv.setTextColor(INACTIVE_COLOR);
        tv.setLineSpacing(0, 1.08f);
        tv.setPadding(0, dpToPx(1), 0, dpToPx(2));
        boolean hasText = line.translation != null && !line.translation.trim().isEmpty();
        if (hasText) {
            tv.setText(line.translation);
        }
        tv.setVisibility(translationEnabled && hasText ? View.VISIBLE : View.GONE);
        return tv;
    }

    public LineView createLineView(LyricsData.LyricsLine line, LinearLayout.LayoutParams params) {
        LineView lv = new LineView();
        lv.line = line;

        if (params == null) {
            params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            int hMargin = dpToPx(24);
            int vMargin = dpToPx(10);
            params.setMargins(hMargin, vMargin, hMargin, 0);
        }

        if (line.isInterlude) {
            LinearLayout container = new LinearLayout(context);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setGravity(Gravity.CENTER);
            container.setLayoutParams(params);
            container.setPadding(0, dpToPx(6), 0, dpToPx(6));

            InterludeDotView dotView = new InterludeDotView(context, line.startTime, line.endTime);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(40));
            dotView.setLayoutParams(dotParams);
            container.addView(dotView);

            lv.interludeView = dotView;
            lv.rootView = container;
            lv.usePerWord = false;
        } else if (syllableMode && hasWordTiming(line)) {
            lv.usePerWord = true;
            lv.rootView = createPerWordView(line, lv, params);
        } else {
            lv.usePerWord = false;
            lv.rootView = createPerLineView(line, lv, params);
        }

        lineViews.add(lv);
        return lv;
    }

    private boolean hasWordTiming(LyricsData.LyricsLine line) {
        if (line.words == null || line.words.isEmpty()) return false;
        for (LyricsData.Word w : line.words) {
            if (w.endTime > w.startTime) return true;
        }
        return false;
    }

    private View createPerWordView(LyricsData.LyricsLine line, LineView lv, LinearLayout.LayoutParams params) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setLayoutParams(params);
        // Let word springs paint into the line margins instead of being sliced at
        // the flow-layout edge (scale pop / lift / glow all overshoot word bounds).
        container.setClipChildren(false);
        container.setClipToPadding(false);

        WordFlowLayout flow = new WordFlowLayout(context);
        LinearLayout.LayoutParams flowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        flow.setLayoutParams(flowParams);
        flow.setPadding(0, dpToPx(6), 0, dpToPx(2));
        // Word views must paint outside their own box too (fireworks bursts and
        // welding sparks fly beyond the word); without this the flow slices them.
        flow.setClipChildren(false);
        flow.setClipToPadding(false);

        List<DisplayWord> displayWords = buildDisplayWords(line);
        lv.wordViews = new ArrayList<>();
        for (int i = 0; i < displayWords.size(); i++) {
            DisplayWord dw = displayWords.get(i);
            String wText = dw.text != null ? dw.text.trim() : "";
            if (wText.isEmpty()) continue;

            String[] tokens = wText.split("\\s+");
            long wordDur = dw.endTime > dw.startTime ? (dw.endTime - dw.startTime) / tokens.length : 0;
            for (int k = 0; k < tokens.length; k++) {
                String tok = tokens[k];
                if (tok.isEmpty()) continue;
                long wStart = dw.startTime + k * wordDur;
                long wEnd = (k == tokens.length - 1) ? dw.endTime : wStart + wordDur;

                GradientWordView wv = new GradientWordView(context);
                wv.setAnimConfig(animConfig);
                wv.setWordIndex(lv.wordViews.size());
                wv.setText(tok);
                wv.setTiming(wStart, wEnd);
                wv.setWordStyle(fontSizeSp, INACTIVE_COLOR, fontBold);
                wv.initLetterEmphasis(tok, wStart, wEnd);

                WordFlowLayout.LayoutParams wlp = new WordFlowLayout.LayoutParams(
                        WordFlowLayout.LayoutParams.WRAP_CONTENT,
                        WordFlowLayout.LayoutParams.WRAP_CONTENT);
                wlp.rightMargin = dpToPx(7);
                wv.setLayoutParams(wlp);

                flow.addView(wv);
                lv.wordViews.add(wv);
            }
        }

        container.addView(flow);
        lv.wordFlowLayout = flow;

        if (hasAnyWordRomanji(line)) {
            setupRomajiLayer(line, lv, container);
        }

        if (line.backgroundVocals != null && !line.backgroundVocals.isEmpty()) {
            lv.backgroundWordViews = new ArrayList<>();
            lv.bgFlowLayouts = new ArrayList<>();
            lv.bgWordLastActive = new ArrayList<>();
            float bgSizeSp = fontSizeSp * 0.75f;
            for (LyricsData.LyricsLine bgLine : line.backgroundVocals) {
                WordFlowLayout bgFlow = new WordFlowLayout(context);
                LinearLayout.LayoutParams bgFlowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                bgFlow.setLayoutParams(bgFlowParams);
                bgFlow.setPadding(dpToPx(4), dpToPx(2), 0, dpToPx(2));

                List<GradientWordView> bgWords = createBackgroundWords(bgFlow, bgLine, bgSizeSp);

                boolean[] bgActive = new boolean[Math.max(1, bgWords.size())];
                container.addView(bgFlow);
                lv.bgFlowLayouts.add(bgFlow);
                lv.backgroundWordViews.add(bgWords);
                lv.bgWordLastActive.add(bgActive);
            }
        }

        TextView tv = createTranslationTextView(line);
        if (tv != null) {
            LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            tv.setLayoutParams(tvLp);
            container.addView(tv);
            lv.translationView = tv;
        }

        return container;
    }
    private void setupRomajiLayer(LyricsData.LyricsLine line, LineView lv, LinearLayout container) {
        if (line == null || line.words == null || line.words.isEmpty()) return;
        WordFlowLayout romajiFlow = new WordFlowLayout(context);
        LinearLayout.LayoutParams romajiParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        romajiFlow.setLayoutParams(romajiParams);
        int topPadding = !originalScriptEnabled ? dpToPx(4) : dpToPx(1);
        romajiFlow.setPadding(0, topPadding, 0, dpToPx(1));
        romajiFlow.setClipChildren(false);
        romajiFlow.setClipToPadding(false);

        List<DisplayWord> displayWords = buildDisplayWords(line);
        List<GradientWordView> romajiWordViews = new ArrayList<>();
        float romajiFontSize = getRomajiFontSize();
        Typeface tf = !originalScriptEnabled ? fontBold : fontMedium;
        int initialColor = lv.lastState == STATE_ACTIVE ? ACTIVE_COLOR : (lv.lastState == STATE_PAST ? PAST_COLOR : INACTIVE_COLOR);
        for (int i = 0; i < displayWords.size(); i++) {
            DisplayWord dw = displayWords.get(i);
            String rText = "";
            if (dw.romanji != null && !dw.romanji.trim().isEmpty()) {
                rText = dw.romanji.trim();
            } else if (dw.text != null && !ScriptDetector.isNonLatin(dw.text)) {
                rText = dw.text.trim();
            }
            if (rText.isEmpty()) continue;

            String[] tokens = rText.split("\\s+");
            long wordDur = dw.endTime > dw.startTime ? (dw.endTime - dw.startTime) / tokens.length : 0;
            for (int k = 0; k < tokens.length; k++) {
                String tok = tokens[k];
                if (tok.isEmpty()) continue;
                long wStart = dw.startTime + k * wordDur;
                long wEnd = (k == tokens.length - 1) ? dw.endTime : wStart + wordDur;

                GradientWordView rwv = new GradientWordView(context);
                rwv.setAnimConfig(animConfig);
                rwv.setWordIndex(romajiWordViews.size());
                rwv.setText(tok);
                rwv.setTiming(wStart, wEnd);
                rwv.setWordStyle(romajiFontSize, initialColor, tf);
                rwv.initLetterEmphasis(tok, wStart, wEnd);
                if (lv.lastState == STATE_ACTIVE) {
                    rwv.setLineActive(true);
                }

                WordFlowLayout.LayoutParams rwlp = new WordFlowLayout.LayoutParams(
                        WordFlowLayout.LayoutParams.WRAP_CONTENT,
                        WordFlowLayout.LayoutParams.WRAP_CONTENT);
                rwlp.rightMargin = !originalScriptEnabled ? dpToPx(6) : dpToPx(4);
                rwv.setLayoutParams(rwlp);

                romajiFlow.addView(rwv);
                romajiWordViews.add(rwv);
            }
        }

        if (lv.romajiWordFlowLayout != null && container != null) {
            container.removeView(lv.romajiWordFlowLayout);
        }

        lv.romajiWordFlowLayout = romajiFlow;
        lv.romajiWordViews = romajiWordViews;

        if (container != null) {
            int flowIndex = lv.wordFlowLayout != null ? container.indexOfChild(lv.wordFlowLayout) : -1;
            if (flowIndex >= 0) {
                container.addView(romajiFlow, flowIndex + 1);
            } else {
                container.addView(romajiFlow);
            }
        }

        updateLineScriptVisibility(lv);
    }

    private View createPerLineView(LyricsData.LyricsLine line, LineView lv, LinearLayout.LayoutParams params) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setLayoutParams(params);

        GradientTextView gv = new GradientTextView(context);
        gv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
        gv.setTextColor(INACTIVE_COLOR);
        gv.setTypeface(fontMedium);
        gv.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams gvParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        gv.setLayoutParams(gvParams);
        gv.setPadding(0, dpToPx(6), 0, dpToPx(2));
        gv.setText(line.text);
        container.addView(gv);
        lv.gradientView = gv;

        if (line.backgroundVocals != null && !line.backgroundVocals.isEmpty()) {
            lv.backgroundWordViews = new ArrayList<>();
            lv.bgFlowLayouts = new ArrayList<>();
            lv.bgWordLastActive = new ArrayList<>();
            float bgSizeSp = fontSizeSp * 0.75f;
            for (LyricsData.LyricsLine bgLine : line.backgroundVocals) {
                WordFlowLayout bgFlow = new WordFlowLayout(context);
                LinearLayout.LayoutParams bgFlowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                bgFlow.setLayoutParams(bgFlowParams);
                bgFlow.setPadding(dpToPx(4), dpToPx(2), 0, dpToPx(2));

                List<GradientWordView> bgWords = createBackgroundWords(bgFlow, bgLine, bgSizeSp);

                boolean[] bgActive = new boolean[Math.max(1, bgWords.size())];
                container.addView(bgFlow);
                lv.bgFlowLayouts.add(bgFlow);
                lv.backgroundWordViews.add(bgWords);
                lv.bgWordLastActive.add(bgActive);
            }
        }

        TextView tv = createTranslationTextView(line);
        if (tv != null) {
            LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            tv.setLayoutParams(tvLp);
            container.addView(tv);
            lv.translationView = tv;
        }

        return container;
    }

    private List<GradientWordView> createBackgroundWords(WordFlowLayout bgFlow,
                                                         LyricsData.LyricsLine bgLine, float bgSizeSp) {
        List<GradientWordView> bgWords = new ArrayList<>();
        if (bgLine.words != null && !bgLine.words.isEmpty()) {
            for (int j = 0; j < bgLine.words.size(); j++) {
                LyricsData.Word bw = bgLine.words.get(j);
                bgWords.add(makeBackgroundWord(bw.text, bw.startTime, bw.endTime, j, bgLine.words.size(), bgSizeSp, bgFlow));
            }
        } else if (bgLine.text != null && !bgLine.text.isEmpty()) {
            long dur = bgLine.endTime - bgLine.startTime;
            String[] parts = bgLine.text.split("\\s+");
            long wordDur = parts.length > 0 ? dur / parts.length : dur;
            for (int j = 0; j < parts.length; j++) {
                long ws = bgLine.startTime + j * wordDur;
                long we = ws + wordDur;
                bgWords.add(makeBackgroundWord(parts[j], ws, we, j, parts.length, bgSizeSp, bgFlow));
            }
        }
        return bgWords;
    }

    private GradientWordView makeBackgroundWord(String text, long start, long end,
                                                int index, int count, float bgSizeSp, WordFlowLayout bgFlow) {
        GradientWordView bwv = new GradientWordView(context);
        bwv.setAnimConfig(animConfig);
        bwv.setText(text);
        bwv.setTiming(start, end);
        bwv.setBackgroundMode(true);
        bwv.setWordStyle(bgSizeSp, 0x55FFFFFF, fontMedium);
        bwv.initLetterEmphasis(text, start, end);
        WordFlowLayout.LayoutParams bwlp = new WordFlowLayout.LayoutParams(
                WordFlowLayout.LayoutParams.WRAP_CONTENT,
                WordFlowLayout.LayoutParams.WRAP_CONTENT);
        if (index < count - 1) {
            bwlp.rightMargin = dpToPx(3);
        }
        bwv.setLayoutParams(bwlp);
        bgFlow.addView(bwv);
        return bwv;
    }

    public void animateInterlude(long positionMs, double deltaTime) {
        for (int i = 0; i < lineViews.size(); i++) {
            LineView lv = lineViews.get(i);
            if (lv.interludeView != null) {
                lv.interludeView.animate(positionMs, deltaTime);
            }
        }
    }

    public void updateProgress(long currentTimeMs) {
        updateHighlight(currentTimeMs, 0.016);
    }

    public void updateHighlight(long currentPosition, double deltaTime) {
        updateHighlight(currentPosition, deltaTime, 0, lineViews.size());
    }

    public void updateHighlight(long currentPosition, double deltaTime, int start, int end) {
        int n = lineViews.size();
        if (n == 0) return;
        if (start < 0) start = 0;
        if (end > n) end = n;
        for (int i = start; i < end; i++) {
            LineView lv = lineViews.get(i);
            LyricsData.LyricsLine line = lv.line;
            if (line.isInterlude) continue;

            boolean isActive = currentPosition >= line.startTime && currentPosition < line.endTime;
            boolean isPast = currentPosition >= line.endTime;
            int state = isActive ? STATE_ACTIVE : (isPast ? STATE_PAST : STATE_UPCOMING);
            boolean stateChanged = state != lv.lastState;

            if (lv.usePerWord && lv.wordViews != null) {
                updatePerWordHighlight(lv, currentPosition, deltaTime, stateChanged, isActive, isPast);
            } else if (lv.gradientView != null) {
                updatePerLineHighlight(lv, currentPosition, deltaTime, stateChanged, isActive, isPast);
            }
            updateLineScale(lv, isActive, deltaTime);

            lv.lastState = state;
        }
    }

    /**
     * The active line grows slightly and relaxes back when it passes — a line-level
     * motion layered under the per-word springs, so every animation variant gets it.
     * Kept tiny: with the left edge anchored (pivotX=0), a larger factor would push
     * the tail of long lines past the container edge where the ScrollView clips it,
     * reading as a cut-off, forward-shifting lyric.
     */
    private static final double ACTIVE_LINE_SCALE = 1.022;

    private void updateLineScale(LineView lv, boolean isActive, double deltaTime) {
        if (lv.rootView == null) return;
        if (lv.lineScale == null) {
            lv.lineScale = new Spring(1.0, 0.5, 0.9);
        }
        lv.lineScale.finalPosition = isActive ? ACTIVE_LINE_SCALE : 1.0;
        if (deltaTime > 0) {
            lv.lineScale.update(deltaTime);
        }
        float s = (float) lv.lineScale.position;
        lv.rootView.setPivotX(0f);
        lv.rootView.setPivotY(lv.rootView.getHeight() / 2f);
        lv.rootView.setScaleX(s);
        lv.rootView.setScaleY(s);
    }

    /** Fade a line to its new resting alpha instead of snapping it. */
    private void animateLineAlpha(LineView lv, float target) {
        if (lv.rootView == null) return;
        if (Math.abs(lv.rootView.getAlpha() - target) < 0.01f
                && lv.rootView.getTranslationY() == 0f) {
            lv.rootView.setAlpha(target);
            return;
        }
        lv.rootView.animate().cancel();
        // translationY is also rehomed: the activation rise could be cancelled
        // mid-flight by this very state change, leaving the line stuck offset.
        lv.rootView.animate()
                .alpha(target)
                .translationY(0f)
                .setDuration(Anim.D_MED - 60)
                .setInterpolator(Anim.STANDARD)
                .start();
    }

    /**
     * Words of a freshly-activated line cascade in (tiny rise + fade, staggered
     * left-to-right). This is the line's "hello" beat before the per-word sweep
     * takes over; kept short so it never fights the singing.
     *
     * Words already at/past their timing (e.g. after a seek into the middle of the
     * line) snap straight to visible — re-fading sung words makes the lyric look
     * like it jumps back and re-advances.
     */
    private void playWordCascade(LineView lv, long position) {
        int wordCount = lv.wordViews != null ? lv.wordViews.size() : 0;
        int romajiCount = lv.romajiWordViews != null ? lv.romajiWordViews.size() : 0;
        int maxWords = Math.max(wordCount, romajiCount);
        if (maxWords == 0) return;

        for (int i = 0; i < maxWords; i++) {
            if (lv.wordViews != null && i < wordCount) {
                GradientWordView wv = lv.wordViews.get(i);
                wv.animate().cancel();
                if (position >= wv.getStartTime() - 300) {
                    wv.setAlpha(1f);
                    wv.setTranslationY(0f);
                } else {
                    wv.setAlpha(0f);
                    wv.setTranslationY(dpToPx(7));
                    wv.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setStartDelay(Math.min(26L * i, 200))
                            .setDuration(230)
                            .setInterpolator(Anim.DECEL)
                            .start();
                }
            }

            if (lv.romajiWordViews != null && i < romajiCount) {
                GradientWordView rwv = lv.romajiWordViews.get(i);
                rwv.animate().cancel();
                if (position >= rwv.getStartTime() - 300) {
                    rwv.setAlpha(1f);
                    rwv.setTranslationY(0f);
                } else {
                    rwv.setAlpha(0f);
                    rwv.setTranslationY(dpToPx(7));
                    rwv.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setStartDelay(Math.min(26L * i, 200))
                            .setDuration(230)
                            .setInterpolator(Anim.DECEL)
                            .start();
                }
            }
        }
    }

    private void updatePerWordHighlight(LineView lv, long position, double deltaTime,
                                         boolean stateChanged, boolean isActive, boolean isPast) {
        for (int w = 0; w < lv.wordViews.size(); w++) {
            GradientWordView wv = lv.wordViews.get(w);
            wv.updateState(position, deltaTime);
        }
        if (lv.romajiWordViews != null) {
            for (int w = 0; w < lv.romajiWordViews.size(); w++) {
                GradientWordView rwv = lv.romajiWordViews.get(w);
                rwv.updateState(position, deltaTime);
            }
        }

        if (stateChanged) {
            for (GradientWordView wv : lv.wordViews) {
                wv.setLineActive(isActive);
                if (!isActive) {
                    wv.animate().cancel();
                    wv.setTranslationY(0f);
                    wv.setAlpha(1f);
                }
            }
            if (lv.romajiWordViews != null) {
                for (GradientWordView rwv : lv.romajiWordViews) {
                    rwv.setLineActive(isActive);
                    if (!isActive) {
                        rwv.animate().cancel();
                        rwv.setTranslationY(0f);
                        rwv.setAlpha(1f);
                    }
                }
            }
            float romajiFontSize = getRomajiFontSize();
            Typeface romajiTf = !originalScriptEnabled ? fontBold : fontMedium;
            if (isActive) {
                if (lv.rootView != null) {
                    lv.rootView.setLayerType(View.LAYER_TYPE_NONE, null);
                }
                animateLineAlpha(lv, 1.0f);
                for (GradientWordView wv : lv.wordViews) {
                    wv.setWordStyle(fontSizeSp, ACTIVE_COLOR, fontBold);
                }
                if (lv.romajiWordViews != null) {
                    for (GradientWordView rwv : lv.romajiWordViews) {
                        rwv.setWordStyle(romajiFontSize, ACTIVE_COLOR, romajiTf);
                    }
                }
                if (lv.translationView != null) {
                    lv.translationView.setTextColor(ACTIVE_COLOR);
                }
                playWordCascade(lv, position);
            } else if (isPast) {
                // Hardware layers buffer at view bounds and would slice the
                // fireworks burst of a line-final word — keep software layers
                // when that effect is active.
                boolean needsOverflow = animConfig != null && animConfig.fireworks;
                if (lv.rootView != null) {
                    lv.rootView.setLayerType(
                            needsOverflow ? View.LAYER_TYPE_NONE : View.LAYER_TYPE_HARDWARE, null);
                }
                animateLineAlpha(lv, 0.82f);
                for (GradientWordView wv : lv.wordViews) {
                    wv.setWordStyle(fontSizeSp, PAST_COLOR, fontBold);
                }
                if (lv.romajiWordViews != null) {
                    for (GradientWordView rwv : lv.romajiWordViews) {
                        rwv.setWordStyle(romajiFontSize, PAST_COLOR, romajiTf);
                    }
                }
                if (lv.translationView != null) {
                    lv.translationView.setTextColor(PAST_COLOR);
                }
            } else {
                animateLineAlpha(lv, 0.42f);
                for (GradientWordView wv : lv.wordViews) {
                    wv.setWordStyle(fontSizeSp, INACTIVE_COLOR, fontBold);
                }
                if (lv.romajiWordViews != null) {
                    for (GradientWordView rwv : lv.romajiWordViews) {
                        rwv.setWordStyle(romajiFontSize, INACTIVE_COLOR, romajiTf);
                    }
                }
                if (lv.translationView != null) {
                    lv.translationView.setTextColor(INACTIVE_COLOR);
                }
            }
        }

        updateBackgroundVocals(lv, position);
    }

    private void updatePerLineHighlight(LineView lv, long position, double deltaTime,
                                         boolean stateChanged, boolean isActive, boolean isPast) {
        GradientTextView gv = lv.gradientView;
        if (isActive) {
            if (stateChanged) {
                if (lv.rootView != null) {
                    lv.rootView.setLayerType(View.LAYER_TYPE_NONE, null);
                    // Whole-line rise, the per-line counterpart of the word cascade.
                    lv.rootView.animate().cancel();
                    lv.rootView.setTranslationY(dpToPx(8));
                    lv.rootView.animate()
                            .alpha(1.0f)
                            .translationY(0f)
                            .setDuration(Anim.D_MED)
                            .setInterpolator(Anim.DECEL)
                            .start();
                }
                gv.setTypeface(fontBold);
                gv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
                gv.setTextColor(ACTIVE_COLOR);
                if (lv.translationView != null) {
                    lv.translationView.setTextColor(ACTIVE_COLOR);
                }
            }
            float progress = calculateProgress(lv.line, position);
            if (stateChanged || Math.abs(progress - lv.lastProgress) > 0.5f) {
                gv.setProgress(progress);
                lv.lastProgress = progress;
            }
        } else if (stateChanged) {
            if (isPast) {
                gv.setTypeface(fontBold);
                gv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
                gv.setTextColor(PAST_COLOR);
                if (lv.translationView != null) {
                    lv.translationView.setTextColor(PAST_COLOR);
                }
                gv.setProgress(100f);
                animateLineAlpha(lv, 0.82f);
            } else {
                gv.setTypeface(fontBold);
                gv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
                gv.setTextColor(INACTIVE_COLOR);
                if (lv.translationView != null) {
                    lv.translationView.setTextColor(INACTIVE_COLOR);
                }
                gv.setProgress(0f);
                animateLineAlpha(lv, 0.42f);
            }
            if (lv.rootView != null) {
                lv.rootView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }
            lv.lastProgress = isPast ? 100f : 0f;
        }

        updateBackgroundVocals(lv, position);
    }

    private void updateBackgroundVocals(LineView lv, long position) {
        if (lv.backgroundWordViews == null || lv.line.backgroundVocals == null) return;
        for (int b = 0; b < lv.backgroundWordViews.size() && b < lv.line.backgroundVocals.size(); b++) {
            List<GradientWordView> bgWords = lv.backgroundWordViews.get(b);
            LyricsData.LyricsLine bgLine = lv.line.backgroundVocals.get(b);
            boolean bgActive = position >= bgLine.startTime && position < bgLine.endTime;
            boolean bgPast = position >= bgLine.endTime;
            boolean[] lastActive = lv.bgWordLastActive.get(b);

            // Spicy EX: bg lines at 0.9× opacity when not active
            if (lv.bgFlowLayouts != null && b < lv.bgFlowLayouts.size()) {
                lv.bgFlowLayouts.get(b).setAlpha(bgActive ? 1.0f : 0.9f);
            }

            if (bgWords != null) {
                for (int w = 0; w < bgWords.size(); w++) {
                    GradientWordView bwv = bgWords.get(w);
                    bwv.updateState(position, 1.0 / 60.0);

                    boolean wasActive = w < lastActive.length && lastActive[w];
                    if (bgActive && !wasActive) {
                        bwv.setWordStyle(fontSizeSp * 0.75f, 0x99FFFFFF, fontMedium);
                    } else if (bgPast && wasActive) {
                        bwv.setWordStyle(fontSizeSp * 0.75f, 0x44FFFFFF, fontMedium);
                    } else if (!bgActive && !bgPast && wasActive) {
                        bwv.setWordStyle(fontSizeSp * 0.75f, 0x55FFFFFF, fontMedium);
                    }
                    if (w < lastActive.length) {
                        lastActive[w] = bgActive;
                    }
                }
            }
        }
    }

    private float calculateProgress(LyricsData.LyricsLine line, long position) {
        if (line.words == null || line.words.isEmpty()) {
            if (line.endTime <= line.startTime) return 100f;
            float ratio = (float)(position - line.startTime) / (float)(line.endTime - line.startTime);
            return Math.max(0f, Math.min(ratio * 100f, 100f));
        }

        float totalWidth = 0;
        float sweptWidth = 0;
        for (int i = 0; i < line.words.size(); i++) {
            LyricsData.Word word = line.words.get(i);
            float wordDuration = Math.max(1f, word.endTime - word.startTime);
            totalWidth += wordDuration;

            if (position >= word.endTime) {
                sweptWidth += wordDuration;
            } else if (position >= word.startTime) {
                float wordProgress = (position - word.startTime) / wordDuration;
                sweptWidth += wordDuration * wordProgress;
            }
        }

        if (totalWidth <= 0) return 100f;
        return Math.max(0f, Math.min((sweptWidth / totalWidth) * 100f, 100f));
    }

    private int dpToPx(int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }

    public void clear() {
        lineViews.clear();
    }
}
