package com.lyricsync.app.renderer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

public class WordFlowLayout extends ViewGroup {

    public WordFlowLayout(Context context) {
        super(context);
    }

    public WordFlowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public static class LayoutParams extends MarginLayoutParams {
        public LayoutParams(int width, int height) {
            super(width, height);
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

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);

            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int childW = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int childH = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;

            if (x + childW > availableWidth && x > 0) {
                x = 0;
                y += rowHeight;
                rowHeight = 0;
            }

            x += childW;
            rowHeight = Math.max(rowHeight, childH);
        }

        int totalHeight = y + rowHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(width, resolveSize(totalHeight, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int availableWidth = r - l - getPaddingLeft() - getPaddingRight();
        int x = getPaddingLeft();
        int y = getPaddingTop();
        int rowHeight = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;

            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int childW = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int childH = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;

            if (x + childW > getPaddingLeft() + availableWidth && x > getPaddingLeft()) {
                x = getPaddingLeft();
                y += rowHeight;
                rowHeight = 0;
            }

            child.layout(x + lp.leftMargin, y + lp.topMargin,
                    x + lp.leftMargin + child.getMeasuredWidth(),
                    y + lp.topMargin + child.getMeasuredHeight());
            x += childW;
            rowHeight = Math.max(rowHeight, childH);
        }
    }
}
