package io.github.ksuzukigh.rokidzoomincamera;

import static org.junit.Assert.assertArrayEquals;
import org.junit.Test;

public final class ZoomMathTest {
    @Test public void cropIsCenteredAndEven() {
        int[] crop = ZoomMath.cropEdges(0, 0, 4000, 3000, 2f, 4f);
        assertArrayEquals(new int[]{1000, 750, 3000, 2250}, crop);
    }

    @Test public void cropClampsToCameraMaximum() {
        int[] crop = ZoomMath.cropEdges(10, 20, 4010, 3020, 9f, 4f);
        assertArrayEquals(new int[]{1510, 1145, 2510, 1895}, crop);
    }

    @Test public void cropNeverZoomsOutBelowOne() {
        assertArrayEquals(new int[]{0, 0, 4032, 3024},
                ZoomMath.cropEdges(0, 0, 4032, 3024, 0.5f, 8f));
    }

    @Test public void barShowsCurrentStep() {
        org.junit.Assert.assertEquals("1× ■□□□□□□□□□□□ 4×", ZoomMath.bar(1f, 4f));
        org.junit.Assert.assertEquals("1× ■■■■■■■■■■■■ 4×", ZoomMath.bar(4f, 4f));
        org.junit.Assert.assertEquals("1× ■■■■■■□□□□□□ 4×", ZoomMath.bar(2.4f, 4f));
    }

    @org.junit.Test public void stepsAreSmoothAndDoNotDrift() {
        float z = 1f;
        for (int i = 0; i < 30; i++) z = ZoomMath.step(z, 1, 0.1f, 4f);
        org.junit.Assert.assertEquals(4f, z, 0.0001f);
        org.junit.Assert.assertEquals(4f, ZoomMath.step(z, 1, 0.1f, 4f), 0.0001f);
        org.junit.Assert.assertEquals(3.9f, ZoomMath.step(z, -1, 0.1f, 4f), 0.0001f);
        org.junit.Assert.assertEquals(1f, ZoomMath.step(1.1f, -1, 0.2f, 4f), 0.0001f);
        org.junit.Assert.assertEquals(1.2f, ZoomMath.step(1f, 1, 0.2f, 4f), 0.0001f);
    }

    @org.junit.Test public void glideMovesInSmallTicksAndStopsOnTarget() {
        float z = 1f;
        int ticks = 0;
        while (Math.abs(z - 1.1f) > 0.0001f && ticks < 100) {
            float next = ZoomMath.glide(z, 1.1f);
            org.junit.Assert.assertTrue(next - z <= 0.0151f);
            org.junit.Assert.assertTrue(next > z);
            z = next;
            ticks++;
        }
        org.junit.Assert.assertEquals(1.1f, z, 0.0001f);
        org.junit.Assert.assertTrue("ticks " + ticks, ticks >= 6 && ticks <= 12);
        org.junit.Assert.assertEquals(2.0f, ZoomMath.glide(2.005f, 2.0f), 0.0001f);
        org.junit.Assert.assertTrue(ZoomMath.glide(4f, 1f) < 4f);
        org.junit.Assert.assertEquals(1.05f, ZoomMath.step(1f, 1, 0.05f, 4f), 0.0001f);
    }
}
