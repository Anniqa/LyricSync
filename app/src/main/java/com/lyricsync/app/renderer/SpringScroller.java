package com.lyricsync.app.renderer;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ScrollView;

public class SpringScroller {
    private float scrollPositionRatio = 0.18f;

    private final ScrollView scrollView;
    private final Spring scrollSpring = new Spring(0, 0.85, 2.5);
    private ValueAnimator currentAnimator;

    public SpringScroller(ScrollView scrollView) {
        this.scrollView = scrollView;
    }

    public void scrollToView(View targetView, boolean smooth) {
        if (scrollView == null || targetView == null) return;

        int scrollTo = targetScrollFor(targetView);

        if (smooth) {
            smoothScrollTo(scrollTo);
        } else {
            scrollSpring.set(scrollTo);
            scrollView.scrollTo(0, scrollTo);
        }
    }

    public void followView(View targetView, double deltaTime) {
        if (scrollView == null || targetView == null) return;
        scrollSpring.finalPosition = targetScrollFor(targetView);
        if (deltaTime <= 0) deltaTime = 1.0 / 60.0;
        int y = (int) Math.max(0, scrollSpring.update(deltaTime));
        scrollView.scrollTo(0, y);
    }

    public void setScrollPositionRatio(float ratio) {
        scrollPositionRatio = Math.max(0.12f, Math.min(0.45f, ratio));
    }

    public boolean isSettled() {
        boolean animatorDone = currentAnimator == null || !currentAnimator.isRunning();
        return scrollSpring.sleeping && animatorDone;
    }

    public void jumpToView(View targetView) {
        if (scrollView == null || targetView == null) return;
        int y = targetScrollFor(targetView);
        scrollSpring.set(y);
        scrollView.scrollTo(0, y);
    }

    private int targetScrollFor(View targetView) {
        int scrollViewHeight = scrollView.getHeight();
        int targetTop = targetView.getTop();
        int targetHeight = targetView.getHeight();
        int scrollTo = (int) (targetTop - scrollViewHeight * scrollPositionRatio + targetHeight / 2.0f);
        int contentHeight = scrollView.getChildCount() > 0 ? scrollView.getChildAt(0).getHeight() : 0;
        int maxScroll = Math.max(0, contentHeight - scrollViewHeight);
        return Math.max(0, Math.min(scrollTo, maxScroll));
    }

    private void smoothScrollTo(int targetY) {
        if (currentAnimator != null && currentAnimator.isRunning()) {
            currentAnimator.cancel();
        }

        int currentY = scrollView.getScrollY();
        int distance = targetY - currentY;

        if (Math.abs(distance) < 5) return;

        currentAnimator = ValueAnimator.ofFloat(0f, 1f);
        currentAnimator.setDuration(calculateDuration(distance));
        currentAnimator.setInterpolator(new DecelerateInterpolator(1.5f));

        final int startY = currentY;
        currentAnimator.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            int newY = startY + (int) (distance * fraction);
            scrollSpring.set(newY);
            scrollView.scrollTo(0, newY);
        });

        currentAnimator.start();
    }

    private long calculateDuration(int distance) {
        long baseDuration = 300;
        long extraDuration = (long) (Math.abs(distance) * 0.3);
        return Math.min(baseDuration + extraDuration, 800);
    }

    public void destroy() {
        if (currentAnimator != null) {
            currentAnimator.cancel();
        }
    }
}
