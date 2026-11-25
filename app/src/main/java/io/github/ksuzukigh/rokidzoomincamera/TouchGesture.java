package io.github.ksuzukigh.rokidzoomincamera;

final class TouchGesture {
    static final long TAP_MAX_MS = 450L;
    static final long DOUBLE_TAP_MS = 380L;
    static final long LONG_PRESS_MS = 850L;

    private TouchGesture() {}

    static int zoomDelta(float downX, float downY, float upX, float upY,
                         float threshold, boolean recording) {
        if (recording) return 0;
        return swipeDirection(downX, downY, upX, upY, threshold);
    }

    /** +1 forward, -1 backward, 0 when the movement is not a horizontal swipe. */
    static int swipeDirection(float downX, float downY, float upX, float upY, float threshold) {
        float dx = upX - downX;
        float dy = upY - downY;
        if (Math.abs(dx) <= threshold || Math.abs(dx) <= Math.abs(dy)) return 0;
        return dx > 0f ? 1 : -1;
    }

    static boolean isTap(float downX, float downY, float upX, float upY,
                         long durationMs, float slop) {
        return Math.abs(upX - downX) <= slop && Math.abs(upY - downY) <= slop
                && durationMs >= 0L && durationMs <= TAP_MAX_MS;
    }

    static boolean movedBeyond(float downX, float downY, float x, float y, float slop) {
        return Math.abs(x - downX) > slop || Math.abs(y - downY) > slop;
    }
}
