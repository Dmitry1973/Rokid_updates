package io.github.ksuzukigh.rokidzoomincamera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Arrays;

import org.junit.Test;

public final class CaptureOptionsTest {
    private static CaptureOptions sample() {
        CaptureOptions o = new CaptureOptions();
        o.setAvailable(Arrays.asList(new int[]{1280, 720}, new int[]{1920, 1080},
                new int[]{1440, 1080}, new int[]{1920, 1080}, new int[]{2560, 1440}),
                Arrays.asList(30, 24, 60, 30));
        return o;
    }

    @Test public void sortsAndDeduplicates() {
        CaptureOptions o = sample();
        o.videoSizeIndex = 0;
        assertArrayEquals(new int[]{2560, 1440}, o.videoSize());
        assertEquals(24, o.fps());
    }

    @Test public void defaultVideoIsFullHd() {
        CaptureOptions o = sample();
        o.selectDefaultVideoSize();
        assertArrayEquals(new int[]{1920, 1080}, o.videoSize());
    }

    @Test public void selectsSavedValues() {
        CaptureOptions o = sample();
        o.selectVideoSize(1440, 1080);
        assertArrayEquals(new int[]{1440, 1080}, o.videoSize());
        assertEquals(4f / 3f, o.videoAspect(), 0.001f);
        o.selectFps(60);
        assertEquals(60, o.fps());
    }

    @Test public void menuValuesWrap() {
        CaptureOptions o = sample();
        o.change(CaptureOptions.ITEM_PHOTO_ASPECT, -1);
        assertEquals(1f, o.photoAspect(), 0.001f);
        o.change(CaptureOptions.ITEM_PHOTO_ASPECT, 1);
        assertEquals(4f / 3f, o.photoAspect(), 0.001f);
        o.change(CaptureOptions.ITEM_TAP_SHUTTER, 1);
        assertFalse(o.tapShutter);
        assertEquals("выкл", o.valueLabel(CaptureOptions.ITEM_TAP_SHUTTER));
    }

    @Test public void previewPlacementIsClamped() {
        CaptureOptions o = new CaptureOptions();
        assertEquals(1f, o.previewScale(), 0.001f);
        assertEquals(0f, o.previewShift(), 0.001f);
        o.change(CaptureOptions.ITEM_PREVIEW_SIZE, 1);
        assertEquals(1f, o.previewScale(), 0.001f);
        o.change(CaptureOptions.ITEM_PREVIEW_SIZE, -1);
        assertEquals(0.9f, o.previewScale(), 0.001f);
        o.change(CaptureOptions.ITEM_PREVIEW_SHIFT, 1);
        assertEquals(-0.05f, o.previewShift(), 0.001f);
        assertEquals("выше на 5%", o.valueLabel(CaptureOptions.ITEM_PREVIEW_SHIFT));
    }

    @Test public void alwaysHasAFrameRate() {
        CaptureOptions o = new CaptureOptions();
        o.setAvailable(Arrays.asList(new int[]{1920, 1080}), Arrays.asList());
        assertEquals(30, o.fps());
    }
}
