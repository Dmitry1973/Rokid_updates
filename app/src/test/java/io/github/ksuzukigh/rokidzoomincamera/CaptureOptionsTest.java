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
        assertEquals(0f, o.photoAspect(), 0.001f);
        assertEquals("весь кадр", o.valueLabel(CaptureOptions.ITEM_PHOTO_ASPECT));
        o.change(CaptureOptions.ITEM_PHOTO_ASPECT, 1);
        assertEquals("16:9 гориз.", o.valueLabel(CaptureOptions.ITEM_PHOTO_ASPECT));
        o.change(CaptureOptions.ITEM_PHOTO_ASPECT, 1);
        assertEquals("9:16 верт.", o.valueLabel(CaptureOptions.ITEM_PHOTO_ASPECT));
        assertEquals(9f / 16f, o.photoAspect(), 0.001f);
        assertEquals(0.1f, o.zoomStep(), 0.001f);
        o.change(CaptureOptions.ITEM_ZOOM_STEP, 1);
        assertEquals(0.2f, o.zoomStep(), 0.001f);
        o.change(CaptureOptions.ITEM_TAP_SHUTTER, 1);
        assertFalse(o.tapShutter);
        assertEquals("выкл", o.valueLabel(CaptureOptions.ITEM_TAP_SHUTTER));
    }

    @Test public void previewPlacementIsClamped() {
        CaptureOptions o = new CaptureOptions();
        // default: 90 % lifted by 5 %, so the top edge stays and the bottom moves up
        assertEquals(0.9f, o.previewScale(), 0.001f);
        assertEquals(-0.05f, o.previewShift(), 0.001f);
        assertEquals(0f, 0.5f - o.previewScale() / 2f + o.previewShift(), 0.001f);
        o.change(CaptureOptions.ITEM_PREVIEW_SIZE, 1);
        assertEquals(1f, o.previewScale(), 0.001f);
        o.change(CaptureOptions.ITEM_PREVIEW_SIZE, 1);
        assertEquals(1f, o.previewScale(), 0.001f);
        o.change(CaptureOptions.ITEM_PREVIEW_SIZE, -1);
        assertEquals(0.9f, o.previewScale(), 0.001f);
        o.change(CaptureOptions.ITEM_PREVIEW_SHIFT, 1);
        assertEquals(-0.1f, o.previewShift(), 0.001f);
        assertEquals("выше на 10%", o.valueLabel(CaptureOptions.ITEM_PREVIEW_SHIFT));
    }

    @Test public void alwaysHasAFrameRate() {
        CaptureOptions o = new CaptureOptions();
        o.setAvailable(Arrays.asList(new int[]{1920, 1080}), Arrays.asList());
        assertEquals(30, o.fps());
    }

    @Test public void photoSizeAndQuality() {
        CaptureOptions o = new CaptureOptions();
        assertEquals(0, o.photoMaxLongSide());
        assertEquals("максимум", o.valueLabel(CaptureOptions.ITEM_PHOTO_SIZE));
        o.change(CaptureOptions.ITEM_PHOTO_SIZE, 1);
        assertEquals(3000, o.photoMaxLongSide());
        assertEquals("до 3000 px", o.valueLabel(CaptureOptions.ITEM_PHOTO_SIZE));
        assertEquals(95, o.jpegQuality());
        o.change(CaptureOptions.ITEM_JPEG_QUALITY, 1);
        assertEquals(100, o.jpegQuality());
        o.change(CaptureOptions.ITEM_JPEG_QUALITY, 1);
        assertEquals(100, o.jpegQuality());
        o.change(CaptureOptions.ITEM_JPEG_QUALITY, -1);
        o.change(CaptureOptions.ITEM_JPEG_QUALITY, -1);
        assertEquals("90%", o.valueLabel(CaptureOptions.ITEM_JPEG_QUALITY));
        o.selectJpegQuality(99);
        assertEquals(70, o.jpegQuality());
    }

    @Test public void menuHasActionItemsAtTheEnd() {
        CaptureOptions o = new CaptureOptions();
        assertEquals(15, CaptureOptions.ITEM_COUNT);
        o.change(CaptureOptions.ITEM_SWIPE_DIR, 1);
        assertEquals("обратный", o.valueLabel(CaptureOptions.ITEM_SWIPE_DIR));
        org.junit.Assert.assertTrue(CaptureOptions.isAction(CaptureOptions.ITEM_HELP));
        org.junit.Assert.assertTrue(CaptureOptions.isAction(CaptureOptions.ITEM_CLOSE));
        assertFalse(CaptureOptions.isAction(CaptureOptions.ITEM_JPEG_QUALITY));
        assertEquals("Закрыть меню", o.itemName(CaptureOptions.ITEM_CLOSE));
    }

    @Test public void frameOnlyModeAndScale() {
        CaptureOptions o = new CaptureOptions();
        org.junit.Assert.assertTrue(o.frameOnly);
        assertEquals("только рамка", o.valueLabel(CaptureOptions.ITEM_VIEW_MODE));
        assertEquals(1f, o.frameScale(), 0.001f);
        o.change(CaptureOptions.ITEM_FRAME_SCALE, 1);
        assertEquals(1.2f, o.frameScale(), 0.001f);
        o.change(CaptureOptions.ITEM_VIEW_MODE, 1);
        assertFalse(o.frameOnly);
        assertEquals("картинка камеры", o.valueLabel(CaptureOptions.ITEM_VIEW_MODE));
    }
}
