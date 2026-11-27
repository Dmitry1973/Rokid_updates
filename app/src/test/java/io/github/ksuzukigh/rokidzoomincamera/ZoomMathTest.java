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
}
