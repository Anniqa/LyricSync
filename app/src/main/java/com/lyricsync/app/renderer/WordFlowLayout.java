package com.lyricsync.app.renderer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

public class WordFlowLayout extends ViewGroup {

    public WordFlowLayout(Context context) {
        super(context);
        init();
    }

    public WordFlowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // Word springs overshoot past their view bounds (scale pop, lift, glow bloom);
        // clipping here would slice glyphs mid-animation and read as "cut letters".
        setClipChildren(false);
        setClipToPadding(false);
    }

    // Reused across layout passes so onLayout allocates nothing; the rows are rebuilt
    // from scratch each pass, so clear() at the start is all the reset needed.
    private final java.util.List<View> rowViews = new java.util.ArrayList<>();
    private final java.util.List<Integer> rowLefts = new java.util.ArrayList<>();

    // Extra vertical gap between wrapped rows. Lives here (not in child margins)
    // because row spacing must scale with the font, and the glow/lift of an
    // active word paints OUTSIDE its bounds (clipping is off) — at small font
    // sizes with zero gap those overpaints read as overlapping lyrics.
    private int rowSpacing = 0;

    public void setRowSpacing(int px) {
        int next = Math.max(0, px);
        if (next == rowSpacing) return;
        rowSpacing = next;
        requestLayout();
    }

    public static class LayoutParams extends MarginLayoutParams {
        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int availableWidth = width - getPaddingLeft() - getPaddingRight();

        int x = 0;
        int y = 0;
        int rowHeight = 0;
        int maxBaseline = 0;
        int maxDescender = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);

            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int childW = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int childH = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            int bl = child.getBaseline();

            if (x + childW > availableWidth && x > 0) {
                x = 0;
                int fullRowH = (maxBaseline > 0) ? Math.max(rowHeight, maxBaseline + maxDescender) : rowHeight;
                y += fullRowH + rowSpacing;
                rowHeight = 0;
                maxBaseline = 0;
                maxDescender = 0;
            }

            x += childW;
            rowHeight = Math.max(rowHeight, childH);
            if (bl > 0) {
                maxBaseline = Math.max(maxBaseline, bl + lp.topMargin);
                maxDescender = Math.max(maxDescender, (child.getMeasuredHeight() - bl) + lp.bottomMargin);
            }
        }

        int lastRowH = (maxBaseline > 0) ? Math.max(rowHeight, maxBaseline + maxDescender) : rowHeight;
        int totalHeight = y + lastRowH + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(width, resolveSize(totalHeight, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int availableWidth = r - l - getPaddingLeft() - getPaddingRight();
        int left = getPaddingLeft();
        int top = getPaddingTop();

        rowViews.clear();
        rowLefts.clear();
        int x = left;
        int rowHeight = 0;
        int maxBaseline = 0;
        int maxDescender = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;

            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int childW = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int childH = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            int bl = child.getBaseline();

            if (x + childW > left + availableWidth && x > left) {
                layoutRow(rowViews, rowLefts, top, maxBaseline);
                int fullRowH = (maxBaseline > 0) ? Math.max(rowHeight, maxBaseline + maxDescender) : rowHeight;
                top += fullRowH + rowSpacing;

                rowViews.clear();
                rowLefts.clear();
                x = left;
                rowHeight = 0;
                maxBaseline = 0;
                maxDescender = 0;
            }

            rowViews.add(child);
            rowLefts.add(x + lp.leftMargin);
            x += childW;
            rowHeight = Math.max(rowHeight, childH);
            if (bl > 0) {
                maxBaseline = Math.max(maxBaseline, bl + lp.topMargin);
                maxDescender = Math.max(maxDescender, (child.getMeasuredHeight() - bl) + lp.bottomMargin);
            }
        }

        if (!rowViews.isEmpty()) {
            layoutRow(rowViews, rowLefts, top, maxBaseline);
        }
    }

    private void layoutRow(java.util.List<View> rowViews, java.util.List<Integer> rowLefts, int rowTop, int maxBaseline) {
        for (int i = 0; i < rowViews.size(); i++) {
            View child = rowViews.get(i);
            int childLeft = rowLefts.get(i);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int bl = child.getBaseline();
            int offset = (maxBaseline > 0 && bl > 0) ? (maxBaseline - (bl + lp.topMargin)) : 0;
            int childTop = rowTop + lp.topMargin + offset;
            child.layout(childLeft, childTop,
                    childLeft + child.getMeasuredWidth(),
                    childTop + child.getMeasuredHeight());
        }
    }
}
