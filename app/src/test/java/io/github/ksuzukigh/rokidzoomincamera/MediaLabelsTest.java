package io.github.ksuzukigh.rokidzoomincamera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MediaLabelsTest {
    @Test public void megapixels() {
        assertEquals("12 МП", MediaLabels.megapixels(4032, 3024));
        assertEquals("9.1 МП", MediaLabels.megapixels(4032, 2268));
        assertEquals("3 МП", MediaLabels.megapixels(2000, 1500));
        assertEquals("0.8 МП", MediaLabels.megapixels(1008, 756));
    }

    @Test public void videoLabels() {
        assertEquals("1080p30", MediaLabels.video(1920, 1080, 30));
        assertEquals("720p60", MediaLabels.video(1280, 720, 60));
        assertEquals("4K30", MediaLabels.video(3840, 2160, 30));
        assertEquals("1080p", MediaLabels.video(1440, 1080));
    }

    @Test public void fileSizes() {
        assertEquals("850 КБ", MediaLabels.fileSize(850L * 1024));
        assertEquals("2.4 МБ", MediaLabels.fileSize((long) (2.4 * 1024 * 1024)));
        assertEquals("120 МБ", MediaLabels.fileSize(120L * 1024 * 1024));
        assertEquals("1.50 ГБ", MediaLabels.fileSize(1536L * 1024 * 1024));
    }
}
