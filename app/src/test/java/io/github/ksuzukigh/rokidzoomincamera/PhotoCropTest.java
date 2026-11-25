package io.github.ksuzukigh.rokidzoomincamera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class PhotoCropTest {
    @Test public void fullFrameNeedsNoCrop() {
        assertNull(PhotoCrop.crop(4032, 3024, 1f, 4f / 3f));
        assertNull(PhotoCrop.crop(4032, 3024, 1f, 0f));
        assertNull(PhotoCrop.crop(3024, 4032, 1f, 0f));
        assertNull(PhotoCrop.crop(4032, 3024, 1f, 3f / 4f, true));
    }

    @Test public void zoomCropsCentre() {
        assertArrayEquals(new int[]{1008, 756, 3024, 2268}, PhotoCrop.crop(4032, 3024, 2f, 4f / 3f));
        assertArrayEquals(new int[]{1512, 1134, 2520, 1890}, PhotoCrop.crop(4032, 3024, 4f, 4f / 3f));
    }

    @Test public void wideAspectCropsTopAndBottom() {
        int[] r = PhotoCrop.crop(4032, 3024, 1f, 16f / 9f);
        assertEquals(4032, r[2] - r[0]);
        assertEquals(2268, r[3] - r[1]);
        assertEquals(378, r[1]);
    }

    @Test public void squareCropsSides() {
        int[] r = PhotoCrop.crop(4032, 3024, 1f, 1f);
        assertEquals(3024, r[2] - r[0]);
        assertEquals(3024, r[3] - r[1]);
        assertEquals(504, r[0]);
    }

    @Test public void sixteenNineIsLandscapeEvenFromPortraitSource() {
        int[] r = PhotoCrop.crop(3024, 4032, 1f, 16f / 9f);
        assertEquals(3024, r[2] - r[0]);
        assertEquals(1700, r[3] - r[1]);
    }

    @Test public void nineSixteenIsPortrait() {
        int[] r = PhotoCrop.crop(4032, 3024, 1f, 9f / 16f);
        assertEquals(1700, r[2] - r[0]);
        assertEquals(3024, r[3] - r[1]);
    }

    @Test public void rotatedSourceIsCroppedForTheViewedOrientation() {
        // Raw 4032x3024 shown turned 90 degrees = viewed 3024 wide x 4032 tall.
        int[] r = PhotoCrop.crop(4032, 3024, 1f, 16f / 9f, true);
        int rawW = r[2] - r[0];
        int rawH = r[3] - r[1];
        // viewed width = raw height, viewed height = raw width
        assertEquals(16f / 9f, (float) rawH / rawW, 0.01f);
        assertEquals(3024, rawH);
        int[] p = PhotoCrop.crop(4032, 3024, 1f, 9f / 16f, true);
        assertEquals(9f / 16f, (float) (p[3] - p[1]) / (p[2] - p[0]), 0.01f);
    }

    @Test public void zoomAndAspectCombine() {
        int[] r = PhotoCrop.crop(4032, 3024, 2f, 16f / 9f);
        assertEquals(2016, r[2] - r[0]);
        assertEquals(1134, r[3] - r[1]);
    }

    @Test public void frameInPortraitBoxKeepsOrientation() {
        float[] wide = PhotoCrop.fitFrame(0f, 0f, 480f, 640f, 16f / 9f);
        assertEquals(480f, wide[2] - wide[0], 0.5f);
        assertEquals(270f, wide[3] - wide[1], 0.5f);
        assertEquals(185f, wide[1], 0.5f);
        float[] tall = PhotoCrop.fitFrame(0f, 0f, 480f, 640f, 9f / 16f);
        assertEquals(360f, tall[2] - tall[0], 0.5f);
        assertEquals(640f, tall[3] - tall[1], 0.5f);
        float[] whole = PhotoCrop.fitFrame(0f, 0f, 480f, 640f, 0f);
        assertEquals(480f, whole[2] - whole[0], 0.5f);
        assertEquals(640f, whole[3] - whole[1], 0.5f);
        float[] video = PhotoCrop.fitFrameAlongLongSide(0f, 0f, 480f, 640f, 16f / 9f);
        assertEquals(360f, video[2] - video[0], 0.5f);
    }

    @Test public void limitSizeKeepsAspectAndNeverUpscales() {
        assertArrayEquals(new int[]{2000, 1500}, PhotoCrop.limitSize(4032, 3024, 2000));
        assertArrayEquals(new int[]{1008, 756}, PhotoCrop.limitSize(1008, 756, 2000));
        assertArrayEquals(new int[]{4032, 3024}, PhotoCrop.limitSize(4032, 3024, 0));
        assertArrayEquals(new int[]{720, 1280}, PhotoCrop.limitSize(2268, 4032, 1280));
    }

    @Test public void sampleSizeStaysAboveTarget() {
        assertEquals(1, PhotoCrop.sampleSize(4032, 3000));
        assertEquals(2, PhotoCrop.sampleSize(4032, 2000));
        assertEquals(2, PhotoCrop.sampleSize(4032, 1280));
        assertEquals(1, PhotoCrop.sampleSize(1008, 2000));
        assertEquals(1, PhotoCrop.sampleSize(4032, 0));
    }

    @Test public void everyFormatAtEveryZoomKeepsItsAspect() {
        float[] aspects = CaptureOptions.PHOTO_ASPECTS;
        float[] zooms = {1f, 1.1f, 1.5f, 2f, 2.7f, 3f, 4f};
        int[][] sources = {{4032, 3024}, {3024, 4032}};
        for (int[] src : sources) {
            for (float a : aspects) {
                float want = a > 0f ? a : (float) src[0] / src[1];
                for (float z : zooms) {
                    int[] r = PhotoCrop.crop(src[0], src[1], z, a);
                    int w = r == null ? src[0] : r[2] - r[0];
                    int h = r == null ? src[1] : r[3] - r[1];
                    String what = src[0] + "x" + src[1] + " aspect " + a + " zoom " + z;
                    assertEquals(what, want, (float) w / h, 0.01f);
                    assertEquals(what, a >= 1f || a <= 0f && src[0] >= src[1], w >= h);
                    assertEquals("centred " + what, src[0], (r == null ? 0 : r[0]) * 2 + w, 2);
                    int[] limited = PhotoCrop.limitSize(w, h, 2000);
                    assertEquals(want, (float) limited[0] / limited[1], 0.01f);
                }
            }
        }
    }
}
