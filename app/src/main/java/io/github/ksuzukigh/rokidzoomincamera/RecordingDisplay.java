package io.github.ksuzukigh.rokidzoomincamera;

import java.util.Locale;

final class RecordingDisplay {
    private RecordingDisplay() {}

    static String label(long elapsedSeconds) {
        long seconds = Math.max(0L, elapsedSeconds);
        String time;
        if (seconds >= 3600L) {
            time = String.format(Locale.US, "%d:%02d:%02d",
                    seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L);
        } else {
            time = String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L);
        }
        return "● ЗАПИСЬ " + time;
    }
}
