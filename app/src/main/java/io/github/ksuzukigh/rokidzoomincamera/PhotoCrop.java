package io.github.ksuzukigh.rokidzoomincamera;

/**
 * Pure geometry for the software zoom / aspect-ratio crop that is applied to
 * saved photos. Aspects are width / height of the photo as viewed, so 16:9 is
 * always landscape and 9:16 always portrait, whatever the sensor orientation.
 */
final class PhotoCrop {
    private PhotoCrop() {}

    /** Crop for an upright (not rotated) source. See {@link #crop(int, int, float, float, boolean)}. */
    static int[] crop(int rawWidth, int rawHeight, float zoom, float aspect) {
        return crop(rawWidth, rawHeight, zoom, aspect, false);
    }

    /**
     * Returns {left, top, right, bottom} of the centred crop inside a raw
     * image, or {@code null} when no crop is needed (the full frame).
     *
     * @param zoom    digital zoom factor, 1.0 or more
     * @param aspect  width / height of the photo as it is viewed (16:9 = 1.78 is
     *                landscape, 9:16 = 0.56 is portrait); 0 or less = keep the
     *                source shape
     * @param rotated true when the raw pixels are shown turned by 90 or 270
     *                degrees (EXIF orientation), so raw width = viewed height
     */
    static int[] crop(int rawWidth, int rawHeight, float zoom, float aspect, boolean rotated) {
        if (rawWidth <= 0 || rawHeight <= 0) return null;
        float z = Math.max(1f, zoom);
        double zw = rawWidth / z;
        double zh = rawHeight / z;
        double w = zw;
        double h = zh;
        if (aspect > 0f) {
            double rawAspect = rotated ? 1.0 / aspect : aspect;
            if (zw / zh > rawAspect) w = zh * rawAspect;
            else h = zw / rawAspect;
        }
        int cw = Math.min(even(w), rawWidth);
        int ch = Math.min(even(h), rawHeight);
        if (cw >= rawWidth - 1 && ch >= rawHeight - 1) return null;
        int left = (rawWidth - cw) / 2;
        int top = (rawHeight - ch) / 2;
        return new int[]{left, top, left + cw, top + ch};
    }

    /**
     * Rectangle of a frame with the given width / height fitted inside a box
     * (0 or less = the whole box). Returns {left, top, right, bottom}.
     */
    static float[] fitFrame(float boxLeft, float boxTop, float boxWidth, float boxHeight,
                            float aspect) {
        float w = boxWidth;
        float h = boxHeight;
        if (aspect > 0f && boxHeight > 0f) {
            if (boxWidth / boxHeight > aspect) w = boxHeight * aspect;
            else h = boxWidth / aspect;
        }
        float left = boxLeft + (boxWidth - w) / 2f;
        float top = boxTop + (boxHeight - h) / 2f;
        return new float[]{left, top, left + w, top + h};
    }

    /**
     * Frame with the given long/short ratio, long side along the box's long
     * side (used for video, whose orientation follows the camera).
     */
    static float[] fitFrameAlongLongSide(float boxLeft, float boxTop, float boxWidth, float boxHeight,
                                         float longOverShort) {
        float a = Math.max(1f, longOverShort);
        return fitFrame(boxLeft, boxTop, boxWidth, boxHeight, boxWidth >= boxHeight ? a : 1f / a);
    }

    /**
     * Output size after limiting the long side to {@code maxLongSide}
     * (0 = no limit). Never upscales. Returns {width, height}.
     */
    static int[] limitSize(int width, int height, int maxLongSide) {
        int longSide = Math.max(width, height);
        if (maxLongSide <= 0 || longSide <= maxLongSide || width <= 0 || height <= 0) {
            return new int[]{width, height};
        }
        double scale = (double) maxLongSide / longSide;
        int w = Math.max(1, (int) Math.round(width * scale));
        int h = Math.max(1, (int) Math.round(height * scale));
        return new int[]{w, h};
    }

    /** Largest power-of-two decoder subsampling that keeps the long side >= the target. */
    static int sampleSize(int longSide, int targetLongSide) {
        if (targetLongSide <= 0 || longSide <= targetLongSide) return 1;
        int sample = 1;
        while (longSide / (sample * 2) >= targetLongSide) sample *= 2;
        return sample;
    }

    private static int even(double value) {
        int v = Math.max(2, (int) Math.round(value));
        return v - (v & 1);
    }
}
