package io.github.ksuzukigh.rokidzoomincamera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RecordingDisplayTest {
    @Test public void showsElapsedTime() {
        assertEquals("● ЗАПИСЬ 00:00", RecordingDisplay.label(0L));
        assertEquals("● ЗАПИСЬ 02:05", RecordingDisplay.label(125L));
        assertEquals("● ЗАПИСЬ 1:01:01", RecordingDisplay.label(3661L));
    }

    @Test public void clampsNegativeElapsedTime() {
        assertEquals("● ЗАПИСЬ 00:00", RecordingDisplay.label(-10L));
    }
}
