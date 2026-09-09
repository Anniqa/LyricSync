# Design Specification: SmartPanel-Style Invisible Swipe & Hold Edge Trigger

- **Date:** 2026-09-09
- **Status:** Approved Design
- **Target:** LyricSync (`com.lyricsync.app`)
- **Package:** `com.lyricsync.app.overlay`

---

## 1. Executive Summary & Goals

### 1.1 Problem Statement
Currently in LyricSync, when the user closes the floating lyrics window via the header close button (`overlayClose`), the service calls `stopSelf()`, terminating the entire foreground service and media tracking. To get lyrics back, the user must leave their current app (game, video, chat), open `MainActivity`, and click the "Start" button (`startButton`).

Furthermore, users wanting quick access without persistent screen clutter require an edge launcher modeled after the Infinix XOS SmartPanel UX. A visible permanent handle or floating bubble would obstruct games, videos, and full-screen apps.

### 1.2 Solution & User Experience
1. **100% Clean Screen (Zero Visual Clutter):**
   When idle or minimized, there are NO visible handles, strips, or floating icons on screen. The edge triggers are completely transparent.
2. **"Swipe & Hold" Gesture (Infinix SmartPanel Pattern):**
   - Quick flicks/swipes from screen edges are ignored by the trigger, passing through to Android system navigation (Back gesture) or app scrolling.
   - Swiping inward from either edge (~20dp) and **holding for ~250ms** triggers the start/spawn logic: a subtle haptic vibration occurs, and the floating lyrics capsule immediately expands onto the screen via `Anim.bubbleIn()`.
3. **Decoupled Window Dismissal vs Service Lifecycle:**
   Tapping the close button (X) on the lyrics header dismisses the floating window smoothly while keeping the foreground service and invisible edge triggers active in the background. The service is only terminated when the user explicitly taps "Stop" in the system notification or in `MainActivity`.
4. **Zero Extra Permissions:**
   Operates entirely within existing `SYSTEM_ALERT_WINDOW` and foreground service permissions. No root, no `AudioManager`, and no system UID required.

---

## 2. Architecture & Component Design

```
┌────────────────────────────────────────────────────────┐
│                   Screen Edges                         │
│  [Left Transparent Zone]    [Right Transparent Zone]   │
│         (12dp x 50% H)              (12dp x 50% H)      │
└───────────────┬───────────────────────────┬────────────┘
                │                           │
                ▼                           ▼
      ┌───────────────────────────────────────────────┐
      │          EdgeSwipeTriggerManager              │
      │  - Manages left/right transparent windows    │
      │  - Detects Swipe & Hold (dx >= 20dp, 250ms)  │
      │  - setSystemGestureExclusionRects (API 29+)  │
      └───────────────────────┬───────────────────────┘
                              │ Trigger Event
                              ▼
      ┌───────────────────────────────────────────────┐
      │           FloatingOverlayService              │
      │  - If running + hidden: showOverlayWindow()  │
      │  - If not running: startForegroundService()   │
      │  - Dismiss (X) hides window, keeps service    │
      │  - Notification "Stop" kills service totally  │
      └───────────────────────────────────────────────┘
```

### 2.1 Component: `EdgeSwipeTriggerManager.java`

- **Role:** Modular controller in `com.lyricsync.app.overlay` managing the edge trigger windows and gesture recognition.
- **Window Specifications:**
  - Window Type: `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`
  - Flags:
    ```java
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    ```
  - Format: `PixelFormat.TRANSLUCENT`
  - Dimensions: Width = 12dp, Height = 45% of display height (centered vertically in thumb zone).
  - Views: Two lightweight invisible views (left edge: `Gravity.TOP | Gravity.START`, right edge: `Gravity.TOP | Gravity.END`).
  - View Background: `@android:color/transparent`.
- **System Gesture Exclusion (API 29+):**
  Registers exclusion rectangles with `view.setSystemGestureExclusionRects(...)` ensuring Android does not consume inward hold gestures as system Back in this region.

### 2.2 Gesture State Machine: "Swipe & Hold"

1. **`ACTION_DOWN`:**
   - Record `downX = event.getRawX()`, `downY = event.getRawY()`.
   - Set `isTriggerArmed = false`.
   - Post `holdCheckRunnable` to handler with delay of `250ms`.
2. **`ACTION_MOVE`:**
   - Calculate `dx = Math.abs(event.getRawX() - downX)`.
   - Calculate `dy = Math.abs(event.getRawY() - downY)`.
   - If `dy > dx * 1.5f`: User is scrolling vertically. Cancel `holdCheckRunnable`.
   - If `dx >= dpToPx(20)`: Mark horizontal intent valid.
3. **Timer Expiry (250ms):**
   - If horizontal intent valid and finger still touching:
     - Set `isTriggerArmed = true`.
     - Trigger `Haptics.tick(view)`.
     - Invoke `OnTriggerListener.onEdgeTriggered()`.
4. **`ACTION_UP` / `ACTION_CANCEL`:**
   - Remove `holdCheckRunnable` callbacks.
   - Reset gesture tracking variables.

---

## 3. Integration with `FloatingOverlayService`

### 3.1 Lifecycle & Window Management
- **Service Creation (`onCreate`):**
  Initialize `EdgeSwipeTriggerManager`. If `pref_edge_trigger_enabled` is true, attach edge trigger windows.
- **Window Dismissal vs Stop:**
  - `hideOverlayWindow()`: Animates `overlayView` scale/fade out and calls `windowManager.removeView(overlayView)`. `isOverlayVisible = false`. Service remains running in foreground.
  - `showOverlayWindow()`: Re-adds `overlayView` if detached, sets visibility, and plays `Anim.bubbleIn(overlayView)`.
  - `overlayClose.setOnClickListener(...)`: Calls `hideOverlayWindow()`, leaving the edge trigger active.
- **Action Intent Handling (`onStartCommand`):**
  Support `ACTION_SHOW_OVERLAY`: If service is already running, invokes `showOverlayWindow()`.

### 3.2 Full Service Termination
The foreground service stops only when:
1. User taps "Stop" in the foreground media notification.
2. User taps "Stop" (`startButton`) in `MainActivity`.
When destroyed (`onDestroy`), `EdgeSwipeTriggerManager.destroy()` removes all trigger views from `WindowManager`.

---

## 4. Settings & Configuration

- **Key:** `pref_edge_trigger_enabled` (boolean, default: `true`).
- **UI Exposure:**
  1. Switch in `MainActivity.java` under Overlay / Controls section.
  2. Switch inside `overlay_settings_panel` in `res/layout/overlay_lyrics.xml`.
- **String Resources:**
  - `pref_edge_trigger_title`: "Edge Gesture Shortcut" (English) / "Pintasan Gestur Tepi" (Indonesian).
  - `pref_edge_trigger_desc`: "Swipe and hold from edge to show lyrics" (English) / "Usap dan tahan dari tepi untuk membuka lirik" (Indonesian).
  - Constraint: No unicode emojis in strings.

---

## 5. Verification & Testing

1. **Unit / Pure Logic Test:**
   - Test gesture math: Verify that `dy > dx` cancels hold, quick flick (`UP` < 250ms) does not trigger, and `dx >= 20dp` + 250ms hold triggers callback.
2. **Regression & Compatibility:**
   - Verify Android gesture navigation Back swipe works normally outside the exclusion zone and on quick flicks.
   - Verify zero touch interception on the rest of the screen (game controls, keyboards, web scrolling).
   - Ensure clean compilation with `./gradlew assembleDebug` (Java 11, API 26-35).
