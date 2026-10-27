package com.lyricsync.app.overlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.lyricsync.app.R;
import com.lyricsync.app.ui.Anim;
import com.lyricsync.app.util.AppLog;
import com.lyricsync.app.util.Haptics;

/**
 * The two secondary windows of the overlay: the floating AI chat and the bubble
 * it minimises to. Owns their WindowManager lifecycle, sizing, drag gestures,
 * bubble edge-snapping and re-fitting after rotation. Chat <em>content</em> (messages,
 * input, tool status) stays with the service through {@link Host}.
 *
 * At most one of the two windows is attached at a time.
 */
final class OverlayChatWindows {

    interface Host {
        /** Inflate and bind the chat content into {@code container}; return its root. */
        View createChatContent(FrameLayout container);

        /** The chat window was just attached (refresh title, quota, templates). */
        void onChatShown();
    }

    private static final String TAG = "OverlayChatWindows";

    private final Context themedContext;
    private final WindowManager windowManager;
    private final OverlayScreen screen;
    private final SharedPreferences prefs;
    private final OverlayConfig config;
    private final Host host;

    private FrameLayout chatContainer;
    private View chatView;
    private WindowManager.LayoutParams chatParams;
    private boolean chatAttached;

    private View bubbleView;
    private WindowManager.LayoutParams bubbleParams;
    private boolean bubbleAttached;

    private boolean released;

    OverlayChatWindows(Context themedContext, WindowManager windowManager, OverlayScreen screen,
                       SharedPreferences prefs, OverlayConfig config, Host host) {
        this.themedContext = themedContext;
        this.windowManager = windowManager;
        this.screen = screen;
        this.prefs = prefs;
        this.config = config;
        this.host = host;
    }

    boolean isChatShown() {
        return chatAttached;
    }

    // ── Chat window ──────────────────────────────────────────────────────────

    void showChat() {
        if (released || windowManager == null) return;
        removeBubble();
        if (chatAttached) return;
        if (chatContainer == null) createChat();

        chatParams = new WindowManager.LayoutParams(
                chatWidthPx(), chatHeightPx(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        chatParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        chatParams.gravity = Gravity.CENTER;

        try {
            windowManager.addView(chatContainer, chatParams);
            chatAttached = true;
            Anim.bubbleIn(chatView);
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to add AI chat overlay", e);
            chatAttached = false;
            return;
        }
        host.onChatShown();
    }

    void minimizeToBubble() {
        if (released || windowManager == null) return;
        removeChat();
        if (bubbleAttached) return;
        if (bubbleView == null) createBubble();

        int size = screen.px(config.bubbleSizeDp);
        bubbleParams = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = prefs.getInt(OverlayPrefKeys.AI_BUBBLE_X,
                OverlayGeometry.bubbleInitialX(config, screen.widthPx(), screen.density()));
        bubbleParams.y = prefs.getInt(OverlayPrefKeys.AI_BUBBLE_Y,
                OverlayGeometry.bubbleInitialY(screen.heightPx()));
        // A position saved in the other orientation may now be off-screen.
        clampBubble();

        try {
            windowManager.addView(bubbleView, bubbleParams);
            bubbleAttached = true;
            Anim.bubbleIn(bubbleView);
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to add bubble view", e);
            bubbleAttached = false;
        }
    }

    /** Close both windows (chat state itself is kept by the service). */
    void dismiss() {
        removeChat();
        removeBubble();
    }

    /** Teardown: close windows and drop listeners so nothing refers back to the service. */
    void release() {
        dismiss();
        released = true;
        if (bubbleView != null) bubbleView.setOnTouchListener(null);
        if (chatView != null) {
            View header = chatView.findViewById(R.id.ai_chat_header);
            if (header != null) header.setOnTouchListener(null);
        }
    }

    /** Re-fit after rotation / screen-size change. */
    void onConfigurationChanged() {
        if (chatAttached && chatParams != null) {
            chatParams.width = chatWidthPx();
            chatParams.height = chatHeightPx();
            clampChat();
            relayout(chatContainer, chatParams);
        }
        if (bubbleAttached && bubbleParams != null) {
            clampBubble();
            relayout(bubbleView, bubbleParams);
        }
    }

    private int chatWidthPx() {
        return OverlayGeometry.chatWidthPx(config, screen.widthPx(), screen.density());
    }

    private int chatHeightPx() {
        return OverlayGeometry.chatHeightPx(config, screen.heightPx(), screen.density());
    }

    private void createChat() {
        chatContainer = new FrameLayout(themedContext) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                // Back minimises instead of leaking the key to the app underneath.
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP) minimizeToBubble();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        chatView = host.createChatContent(chatContainer);
        View header = chatView != null ? chatView.findViewById(R.id.ai_chat_header) : null;
        if (header != null) installChatDrag(header);
    }

    private void removeChat() {
        if (!chatAttached || chatContainer == null) return;
        chatAttached = false;
        try {
            windowManager.removeView(chatContainer);
        } catch (Exception e) {
            AppLog.w(TAG, "Error removing aiChatContainer: " + e.getMessage());
        }
    }

    private void clampChat() {
        chatParams.x = OverlayGeometry.clampCentered(chatParams.x, screen.widthPx(), chatParams.width);
        chatParams.y = OverlayGeometry.clampCentered(chatParams.y, screen.heightPx(), chatParams.height);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void installChatDrag(View header) {
        header.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private int startX;
            private int startY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = chatParams != null ? chatParams.x : 0;
                        startY = chatParams != null ? chatParams.y : 0;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        int slop = screen.px(config.chatDragSlopDp);
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) dragging = true;
                        if (dragging && chatParams != null) {
                            chatParams.x = startX + (int) dx;
                            chatParams.y = startY + (int) dy;
                            clampChat();
                            relayout(chatContainer, chatParams);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) Haptics.tick(v);
                        return dragging;
                    default:
                        return false;
                }
            }
        });
    }

    // ── Bubble ───────────────────────────────────────────────────────────────

    @SuppressLint("InflateParams") // a window root has no parent to inflate against
    private void createBubble() {
        bubbleView = LayoutInflater.from(themedContext).inflate(R.layout.overlay_ai_bubble, null);
        installBubbleGestures(bubbleView);
    }

    private void removeBubble() {
        if (!bubbleAttached || bubbleView == null) return;
        bubbleAttached = false;
        try {
            windowManager.removeView(bubbleView);
        } catch (Exception e) {
            AppLog.w(TAG, "Error removing bubble view: " + e.getMessage());
        }
    }

    private void clampBubble() {
        int size = screen.px(config.bubbleSizeDp);
        bubbleParams.x = OverlayGeometry.clampFromEdge(bubbleParams.x, screen.widthPx(), size);
        bubbleParams.y = OverlayGeometry.clampFromEdge(bubbleParams.y, screen.heightPx(), size);
    }

    /** Drag anywhere; release snaps to the nearer side edge. A tap re-opens the chat. */
    @SuppressLint("ClickableViewAccessibility")
    private void installBubbleGestures(View bubble) {
        bubble.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private int startX;
            private int startY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = bubbleParams != null ? bubbleParams.x : 0;
                        startY = bubbleParams != null ? bubbleParams.y : 0;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        int slop = screen.px(config.bubbleDragSlopDp);
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) dragging = true;
                        if (dragging && bubbleParams != null) {
                            bubbleParams.x = startX + (int) dx;
                            bubbleParams.y = startY + (int) dy;
                            clampBubble();
                            relayout(bubbleView, bubbleParams);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            if (bubbleParams != null) {
                                bubbleParams.x = OverlayGeometry.bubbleSnapX(config, bubbleParams.x,
                                        screen.widthPx(), screen.density());
                                relayout(bubbleView, bubbleParams);
                                prefs.edit()
                                        .putInt(OverlayPrefKeys.AI_BUBBLE_X, bubbleParams.x)
                                        .putInt(OverlayPrefKeys.AI_BUBBLE_Y, bubbleParams.y)
                                        .apply();
                            }
                            Haptics.tick(v);
                        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            Haptics.confirm(v);
                            showChat();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void relayout(View view, WindowManager.LayoutParams lp) {
        if (view == null || lp == null) return;
        try {
            windowManager.updateViewLayout(view, lp);
        } catch (Exception e) {
            AppLog.w(TAG, "updateViewLayout failed: " + e.getMessage());
        }
    }
}
