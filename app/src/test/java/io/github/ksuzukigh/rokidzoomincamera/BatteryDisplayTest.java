package io.github.ksuzukigh.rokidzoomincamera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class BatteryDisplayTest {
    @Test public void convertsAndClampsPercentage() {
        assertEquals(83, BatteryDisplay.percentage(83, 100));
        assertEquals(50, BatteryDisplay.percentage(1, 2));
        assertEquals(100, BatteryDisplay.percentage(120, 100));
        assertEquals(-1, BatteryDisplay.percentage(-1, 100));
        assertEquals(-1, BatteryDisplay.percentage(10, 0));
    }

    @Test public void describesBatteryWithoutRelyingOnColor() {
        assertEquals("Бат. 83%", BatteryDisplay.label(83, false));
        assertEquals("Мало 20%", BatteryDisplay.label(20, false));
        assertEquals("Зарядите 10%", BatteryDisplay.label(10, false));
        assertEquals("Заряд 8%", BatteryDisplay.label(8, true));
        assertEquals("Бат. --%", BatteryDisplay.label(-1, false));
    }
}
