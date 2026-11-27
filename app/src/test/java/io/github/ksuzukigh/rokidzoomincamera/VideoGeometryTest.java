package io.github.ksuzukigh.rokidzoomincamera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public final class VideoGeometryTest {
    private static final List<int[]> SIZES = Arrays.asList(
            new int[]{3840, 2160}, new int[]{1920, 1440}, new int[]{1920, 1080}, new int[]{1280, 720});

    @Test public void landscapeFromTurnedCamera() {
        // Sensor 3840x2160 turned 90 = upright 2160x3840; a 16:9 crop is 2160x1215, scaled to 1920 long.
        assertArrayEquals(new int[]{1920, 1072}, VideoGeometry.outputSize(3840, 2160, 90, 16f / 9f, 1920));
        assertArrayEquals(new int[]{1440, 800}, VideoGeometry.outputSize(1920, 1440, 90, 16f / 9f, 1920));
    }

    @Test public void portraitFromTurnedCameraKeepsFullSize() {
        assertArrayEquals(new int[]{1080, 1920}, VideoGeometry.outputSize(1920, 1080, 90, 9f / 16f, 1920));
    }

    @Test public void choosesStreamThatReachesTheTarget() {
        assertArrayEquals(new int[]{3840, 2160}, VideoGeometry.chooseSource(SIZES, 90, 16f / 9f, 1920));
        // 4:3 landscape: 1920x1440 turned = 1440x1920 -> 1440x1080 crop; 4K gives 2160x1620 -> reaches 1920
        assertArrayEquals(new int[]{3840, 2160}, VideoGeometry.chooseSource(SIZES, 90, 4f / 3f, 1920));
        // small target: the smallest stream that reaches it wins
        assertArrayEquals(new int[]{1280, 720}, VideoGeometry.chooseSource(SIZES, 90, 9f / 16f, 1280));
    }

    @Test public void everyShapeComesOutInItsOrientation() {
        float[] shapes = {16f / 9f, 9f / 16f, 4f / 3f, 3f / 4f, 1f};
        int[] rotations = {0, 90, 180, 270};
        for (float a : shapes) {
            for (int r : rotations) {
                int[] src = VideoGeometry.chooseSource(SIZES, r, a, 1920);
                int[] out = VideoGeometry.outputSize(src[0], src[1], r, a, 1920);
                String what = "aspect " + a + " rot " + r;
                assertEquals(what, a, (float) out[0] / out[1], 0.03f);
                assertEquals(what, 0, out[0] % 8 == 0 || out[0] == 1080 ? 0 : 1);
            }
        }
    }

    @Test public void texCoordsWithoutRotationAreTheCrop() {
        // upright 1920x1080, square crop: x from 0.21875 to 0.78125, full height
        float[] t = VideoGeometry.texCoords(0, 1f, 1920, 1080);
        assertEquals(0.21875f, t[0], 1e-4f); // bottom-left s
        assertEquals(0f, t[1], 1e-4f);       // bottom-left t (bottom)
        assertEquals(0.78125f, t[2], 1e-4f); // bottom-right s
        assertEquals(1f, t[5], 1e-4f);       // top-left t (top)
    }

    @Test public void texCoordsTurned90MapUprightTopToSensorLeft() {
        // Turned 90 clockwise: upright top row comes from the sensor's left column (sx = 0).
        float[] t = VideoGeometry.texCoords(90, 0f + 9f / 16f, 1080, 1920);
        // top-left corner (index 2) = upright (0,0) -> sensor (0,1) -> tex (0,0)
        assertEquals(0f, t[4], 1e-4f);
        assertEquals(0f, t[5], 1e-4f);
        // top-right (index 3) = upright (1,0) -> sensor (0,0) -> tex (0,1)
        assertEquals(0f, t[6], 1e-4f);
        assertEquals(1f, t[7], 1e-4f);
        // bottom-left (index 0) = upright (0,1) -> sensor (1,1) -> tex (1,0)
        assertEquals(1f, t[0], 1e-4f);
        assertEquals(0f, t[1], 1e-4f);
    }

    @Test public void encoderSides() {
        assertEquals(1080, VideoGeometry.encoderSide(1080.4));
        assertEquals(1072, VideoGeometry.encoderSide(1079.9));
        assertEquals(800, VideoGeometry.encoderSide(810));
    }

    @Test public void residualRotationFollowsTheCameraMatrix() {
        // column-major 4x4; typical camera matrices: plain y-flip, and y-flip + 90 degree turn
        float[] flipOnly = {1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1};
        float[] turned = {0, -1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1};
        for (int r : new int[]{0, 90, 180, 270}) {
            assertEquals(r, VideoGeometry.residualRotation(flipOnly, r));
            assertEquals(0, VideoGeometry.residualRotation(turned, r));
        }
    }

    @Test public void everyShapeAndSizeCropsInsideTheTexture() {
        float[] shapes = {16f / 9f, 9f / 16f, 4f / 3f, 3f / 4f, 1f};
        for (int[] src : SIZES) {
            for (float a : shapes) {
                int[] up = VideoGeometry.upright(src[0], src[1], 90);
                float[] t = VideoGeometry.texCoords(0, a, up[0], up[1]);
                float w = t[2] - t[0];
                float h = t[5] - t[1];
                String what = src[0] + "x" + src[1] + " aspect " + a;
                // same orientation as the output: width/height in pixels equals the aspect
                assertEquals(what, a, w * up[0] / (h * up[1]), 0.01f);
                for (float v : t) org.junit.Assert.assertTrue(what, v >= -1e-4f && v <= 1f + 1e-4f);
            }
        }
    }
}
