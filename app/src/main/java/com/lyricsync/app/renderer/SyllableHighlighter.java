package com.lyricsync.app.renderer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import com.lyricsync.app.lyrics.model.LyricsData;

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

    public static class LineView {
        public View rootView;
        public GradientTextView gradientView;
        public InterludeDotView interludeView;
        public WordFlowLayout wordFlowLayout;
        public List<GradientWordView> wordViews;
        public List<List<GradientWordView>> backgroundWordViews;
        public List<boolean[]> bgWordLastActive;
        public List<View> bgFlowLayouts;
        public LyricsData.LyricsLine line;
        public boolean usePerWord;
        public int lastState = -1;
        public float lastProgress = -1f;
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

        WordFlowLayout flow = new WordFlowLayout(context);
        LinearLayout.LayoutParams flowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        flow.setLayoutParams(flowParams);
        flow.setPadding(0, dpToPx(6), 0, dpToPx(2));

        lv.wordViews = new ArrayList<>();
        for (int i = 0; i < line.words.size(); i++) {
            LyricsData.Word word = line.words.get(i);
            GradientWordView wv = new GradientWordView(context);
            wv.setText(word.text);
            wv.setTiming(word.startTime, word.endTime);
            wv.setWordStyle(fontSizeSp, INACTIVE_COLOR, fontBold);
            wv.initLetterEmphasis(word.text, word.startTime, word.endTime);

            WordFlowLayout.LayoutParams wlp = new WordFlowLayout.LayoutParams(
                    WordFlowLayout.LayoutParams.WRAP_CONTENT,
                    WordFlowLayout.LayoutParams.WRAP_CONTENT);
            if (i < line.words.size() - 1) {
                wlp.rightMargin = dpToPx(2);
            }
            wv.setLayoutParams(wlp);

            flow.addView(wv);
            lv.wordViews.add(wv);
        }

        container.addView(flow);
        lv.wordFlowLayout = flow;

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

        return container;
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
            boolean isUpcoming = !isActive && !isPast && (line.startTime - currentPosition) < 3000;
            int state = isActive ? STATE_ACTIVE : (isPast ? STATE_PAST : STATE_UPCOMING);
            boolean stateChanged = state != lv.lastState;

            if (lv.usePerWord && lv.wordViews != null) {
                updatePerWordHighlight(lv, currentPosition, deltaTime, stateChanged, isActive, isPast);
            } else if (lv.gradientView != null) {
                updatePerLineHighlight(lv, currentPosition, stateChanged, isActive, isPast);
            }

            lv.lastState = state;
        }
    }

    private void updatePerWordHighlight(LineView lv, long position, double deltaTime,
                                         boolean stateChanged, boolean isActive, boolean isPast) {
        for (int w = 0; w < lv.wordViews.size(); w++) {
            GradientWordView wv = lv.wordViews.get(w);
            wv.updateState(position, deltaTime);
        }

        if (stateChanged) {
            if (isActive) {
                lv.rootView.setLayerType(View.LAYER_TYPE_NONE, null);
                lv.rootView.setAlpha(1.0f);
                for (GradientWordView wv : lv.wordViews) {
                    wv.setWordStyle(fontSizeSp, ACTIVE_COLOR, fontBold);
                }
            } else if (isPast) {
                lv.rootView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                lv.rootView.setAlpha(0.82f);
                for (GradientWordView wv : lv.wordViews) {
                    wv.setWordStyle(fontSizeSp, PAST_COLOR, fontBold);
                }
            } else {
                lv.rootView.setAlpha(0.42f);
                for (GradientWordView wv : lv.wordViews) {
                    wv.setWordStyle(fontSizeSp, INACTIVE_COLOR, fontBold);
                }
            }
        }

        updateBackgroundVocals(lv, position, stateChanged);
    }

    private void updatePerLineHighlight(LineView lv, long position,
                                         boolean stateChanged, boolean isActive, boolean isPast) {
        GradientTextView gv = lv.gradientView;
        if (isActive) {
            if (stateChanged) {
                lv.rootView.setLayerType(View.LAYER_TYPE_NONE, null);
                lv.rootView.setAlpha(1.0f);
                gv.setTypeface(fontBold);
                gv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
                gv.setTextColor(ACTIVE_COLOR);
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
                gv.setProgress(100f);
                lv.rootView.setAlpha(0.82f);
            } else {
                gv.setTypeface(fontBold);
                gv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
                gv.setTextColor(INACTIVE_COLOR);
                gv.setProgress(0f);
                lv.rootView.setAlpha(0.42f);
            }
            lv.rootView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            lv.lastProgress = isPast ? 100f : 0f;
        }

        updateBackgroundVocals(lv, position, stateChanged);
    }

    private void updateBackgroundVocals(LineView lv, long position, boolean stateChanged) {
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
