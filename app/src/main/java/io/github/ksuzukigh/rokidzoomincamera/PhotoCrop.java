package io.github.ksuzukigh.rokidzoomincamera;

/**
 * Pure geometry for the software zoom / aspect-ratio crop that is applied to
 * saved photos. The long side of the requested aspect always runs along the
 * long side of the source image, so EXIF rotation does not change the result.
 */
final class PhotoCrop {
    private PhotoCrop() {}

    /**
     * Returns {left, top, right, bottom} of the centred crop inside a raw
     * image, or {@code null} when no crop is needed (the full frame).
     *
     * @param zoom   digital zoom factor, 1.0 or more
     * @param aspect long side / short side of the wanted frame, 1.0 or more
     */
    static int[] crop(int rawWidth, int rawHeight, float zoom, float aspect) {
        if (rawWidth <= 0 || rawHeight <= 0) return null;
        float z = Math.max(1f, zoom);
        float a = Math.max(1f, aspect);
        boolean landscape = rawWidth >= rawHeight;
        double longSide = (landscape ? rawWidth : rawHeight) / z;
        double shortSide = (landscape ? rawHeight : rawWidth) / z;
        double cropLong = longSide;
        double cropShort = shortSide;
        if (longSide / shortSide > a) {
            cropLong = shortSide * a;
        } else {
            cropShort = longSide / a;
        }
        int w = even(landscape ? cropLong : cropShort);
        int h = even(landscape ? cropShort : cropLong);
        w = Math.min(w, rawWidth);
        h = Math.min(h, rawHeight);
        if (w >= rawWidth - 1 && h >= rawHeight - 1) return null;
        int left = (rawWidth - w) / 2;
        int top = (rawHeight - h) / 2;
        return new int[]{left, top, left + w, top + h};
    }

    /**
     * Rectangle of a frame with the given aspect fitted inside a box, long
     * side along the box's long side. Returns {left, top, right, bottom}.
     */
    static float[] fitFrame(float boxLeft, float boxTop, float boxWidth, float boxHeight,
                            float aspect) {
        float a = Math.max(1f, aspect);
        boolean landscape = boxWidth >= boxHeight;
        float longSide = landscape ? boxWidth : boxHeight;
        float shortSide = landscape ? boxHeight : boxWidth;
        float frameLong = longSide;
        float frameShort = shortSide;
        if (longSide / shortSide > a) frameLong = shortSide * a;
        else frameShort = longSide / a;
        float w = landscape ? frameLong : frameShort;
        float h = landscape ? frameShort : frameLong;
        float left = boxLeft + (boxWidth - w) / 2f;
        float top = boxTop + (boxHeight - h) / 2f;
        return new float[]{left, top, left + w, top + h};
    }

    private static int even(double value) {
        int v = Math.max(2, (int) Math.round(value));
        return v - (v & 1);
    }
}
