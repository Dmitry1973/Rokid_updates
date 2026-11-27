package io.github.ksuzukigh.rokidzoomincamera;

import java.util.List;

/**
 * Geometry for recording a video in a shape the camera does not give directly
 * (for example landscape 16:9 from a camera whose picture is upright only when
 * turned 90 degrees). The camera stream is drawn through OpenGL: turned upright,
 * cropped to the wanted shape and scaled to the output size. Pure Java.
 *
 * Coordinates: "upright" = the picture as viewed, x to the right, y down,
 * normalised 0..1. "Sensor" = the camera buffer, same convention.
 */
final class VideoGeometry {
    private VideoGeometry() {}

    /** Encoder sizes are kept to multiples of 16, except the common 1080. */
    static int encoderSide(double value) {
        int v = (int) Math.floor(value);
        if (v >= 1080 && v < 1088) return 1080;
        return Math.max(16, v - v % 16);
    }

    /** Upright size of a sensor-oriented buffer. */
    static int[] upright(int sensorW, int sensorH, int rotation) {
        boolean turned = rotation == 90 || rotation == 270;
        return turned ? new int[]{sensorH, sensorW} : new int[]{sensorW, sensorH};
    }

    /** Largest centred crop with width / height = aspect inside an upright w x h picture. */
    static double[] cropSize(int uprightW, int uprightH, float aspect) {
        double w = uprightW;
        double h = uprightH;
        if (w / h > aspect) w = h * aspect;
        else h = w / aspect;
        return new double[]{w, h};
    }

    /**
     * Output video size {width, height} as viewed: the crop scaled down so its
     * long side is at most maxLong (never scaled up).
     */
    static int[] outputSize(int sensorW, int sensorH, int rotation, float aspect, int maxLong) {
        int[] u = upright(sensorW, sensorH, rotation);
        double[] c = cropSize(u[0], u[1], aspect);
        double scale = Math.min(1.0, maxLong / Math.max(c[0], c[1]));
        return new int[]{encoderSide(c[0] * scale), encoderSide(c[1] * scale)};
    }

    /**
     * Picks the camera stream to draw from: the smallest one whose crop already
     * reaches maxLong on its long side, otherwise the one with the largest crop.
     * Sizes are {width, height} in sensor orientation.
     */
    static int[] chooseSource(List<int[]> sizes, int rotation, float aspect, int maxLong) {
        int[] best = null;
        double bestLong = -1;
        long bestPixels = Long.MAX_VALUE;
        for (int[] s : sizes) {
            int[] u = upright(s[0], s[1], rotation);
            double[] c = cropSize(u[0], u[1], aspect);
            double cropLong = Math.min(maxLong, Math.max(c[0], c[1]));
            long pixels = (long) s[0] * s[1];
            boolean better = cropLong > bestLong + 0.5
                    || (Math.abs(cropLong - bestLong) <= 0.5 && pixels < bestPixels);
            if (better) {
                best = s;
                bestLong = cropLong;
                bestPixels = pixels;
            }
        }
        return best;
    }

    /** Sensor point for an upright point; the picture is turned clockwise by rotation to be upright. */
    static float[] toSensor(float x, float y, int rotation) {
        switch (rotation) {
            case 90: return new float[]{y, 1f - x};
            case 180: return new float[]{1f - x, 1f - y};
            case 270: return new float[]{1f - y, x};
            default: return new float[]{x, y};
        }
    }

    /**
     * Texture coordinates (before the SurfaceTexture matrix) for the four corners
     * of a full-viewport triangle strip: bottom-left, bottom-right, top-left,
     * top-right. GL texture t runs upward, so t = 1 - sensor y.
     */
    static float[] texCoords(int rotation, float aspect, int uprightW, int uprightH) {
        double[] c = cropSize(uprightW, uprightH, aspect);
        float cw = (float) (c[0] / uprightW);
        float ch = (float) (c[1] / uprightH);
        float left = (1f - cw) / 2f;
        float top = (1f - ch) / 2f;
        float[] us = {0f, 1f, 0f, 1f};
        float[] vs = {0f, 0f, 1f, 1f}; // GL output: v = 0 is the bottom of the frame
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            float x = left + us[i] * cw;
            float y = top + (1f - vs[i]) * ch;
            float[] s = toSensor(x, y, rotation);
            out[i * 2] = s[0];
            out[i * 2 + 1] = 1f - s[1];
        }
        return out;
    }
}
