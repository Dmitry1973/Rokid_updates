package io.github.ksuzukigh.rokidzoomincamera;

import android.graphics.Rect;

final class ZoomMath {
    private ZoomMath() {}

    static float clamp(float requested, float maximum) {
        return Math.max(1f, Math.min(requested, Math.max(1f, maximum)));
    }

    static final int BAR_CELLS = 12;

    /** Text zoom scale for the HUD, e.g. "1× ■■■■□□□□□□□□ 4×"; 1× = 1 cell, max = all. */
    static String bar(float zoom, float maximum) {
        float max = Math.max(1.0001f, maximum);
        float t = (Math.max(1f, Math.min(zoom, max)) - 1f) / (max - 1f);
        int filled = 1 + Math.round(t * (BAR_CELLS - 1));
        StringBuilder sb = new StringBuilder("1× ");
        for (int i = 0; i < BAR_CELLS; i++) sb.append(i < filled ? '■' : '□');
        return sb.append(String.format(java.util.Locale.US, " %.0f×", max)).toString();
    }

    static final long GLIDE_TICK_MS = 33L;

    /**
     * One animation tick from the current zoom toward the target: at least 0.01x,
     * faster when far away (about 15 % of the gap), never past the target. At 30
     * ticks/s a 0.1x click takes ~0.3 s, so the video shows no visible steps.
     */
    static float glide(float current, float target) {
        float gap = target - current;
        if (Math.abs(gap) <= 0.01f) return target;
        float move = Math.max(0.01f, Math.abs(gap) * 0.15f);
        float next = current + Math.signum(gap) * move;
        return Math.round(next * 1000f) / 1000f;
    }

    /** One zoom step, rounded to 0.01 so repeated steps never drift; kept in [1, maximum]. */
    static float step(float zoom, int direction, float stepSize, float maximum) {
        float next = zoom + (direction >= 0 ? stepSize : -stepSize);
        next = Math.round(next * 100f) / 100f;
        return Math.max(1f, Math.min(next, Math.max(1f, maximum)));
    }

    static Rect crop(Rect sensor, float requestedZoom, float maximumZoom) {
        int[] edges = cropEdges(sensor.left, sensor.top, sensor.right, sensor.bottom,
                requestedZoom, maximumZoom);
        return new Rect(edges[0], edges[1], edges[2], edges[3]);
    }

    static int[] cropEdges(int sensorLeft, int sensorTop, int sensorRight, int sensorBottom,
                           float requestedZoom, float maximumZoom) {
        float zoom = clamp(requestedZoom, maximumZoom);
        int sensorWidth = sensorRight - sensorLeft;
        int sensorHeight = sensorBottom - sensorTop;
        int width = Math.max(2, Math.round(sensorWidth / zoom));
        int height = Math.max(2, Math.round(sensorHeight / zoom));
        width -= width & 1;
        height -= height & 1;
        int centerX = sensorLeft + sensorWidth / 2;
        int centerY = sensorTop + sensorHeight / 2;
        int left = centerX - width / 2;
        int top = centerY - height / 2;
        return new int[]{left, top, left + width, top + height};
    }
}
