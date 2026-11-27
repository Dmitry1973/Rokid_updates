package io.github.ksuzukigh.rokidzoomincamera;

import java.util.Locale;

/** Short HUD labels: "9 МП", "1080p30", "4K30", "2.4 МБ". Pure Java for tests. */
final class MediaLabels {
    private MediaLabels() {}

    static String megapixels(int width, int height) {
        double mp = (double) width * height / 1_000_000d;
        if (mp >= 9.95) return Math.round(mp) + " МП";
        return String.format(Locale.US, "%.1f МП", mp).replace(".0 ", " ");
    }

    /** Video resolution by its short side: 4K, 1440p, 1080p, 720p… */
    static String video(int width, int height) {
        int shortSide = Math.min(width, height);
        int longSide = Math.max(width, height);
        if (shortSide >= 4320 || longSide >= 7680) return "8K";
        if (shortSide >= 2160 || longSide >= 3840) return "4K";
        return shortSide + "p";
    }

    static String video(int width, int height, int fps) {
        return video(width, height) + fps;
    }

    static String fileSize(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < 1024L * 1024L) return Math.max(1L, Math.round(bytes / 1024d)) + " КБ";
        double mb = bytes / (1024d * 1024d);
        if (mb < 1024d) return String.format(Locale.US, mb < 100 ? "%.1f МБ" : "%.0f МБ", mb);
        return String.format(Locale.US, "%.2f ГБ", mb / 1024d);
    }
}
