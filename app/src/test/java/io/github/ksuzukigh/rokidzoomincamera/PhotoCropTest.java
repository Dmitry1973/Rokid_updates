package io.github.ksuzukigh.rokidzoomincamera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class PhotoCropTest {
    @Test public void fullFrameNeedsNoCrop() {
        assertNull(PhotoCrop.crop(4032, 3024, 1f, 4f / 3f));
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

    @Test public void portraitSourceKeepsLongSideVertical() {
        int[] r = PhotoCrop.crop(3024, 4032, 1f, 16f / 9f);
        assertEquals(2268, r[2] - r[0]);
        assertEquals(4032, r[3] - r[1]);
    }

    @Test public void zoomAndAspectCombine() {
        int[] r = PhotoCrop.crop(4032, 3024, 2f, 16f / 9f);
        assertEquals(2016, r[2] - r[0]);
        assertEquals(1134, r[3] - r[1]);
    }

    @Test public void frameFitsInsidePortraitBox() {
        float[] r = PhotoCrop.fitFrame(0f, 0f, 480f, 640f, 16f / 9f);
        assertEquals(360f, r[2] - r[0], 0.5f);
        assertEquals(640f, r[3] - r[1], 0.5f);
        assertEquals(60f, r[0], 0.5f);
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
}
