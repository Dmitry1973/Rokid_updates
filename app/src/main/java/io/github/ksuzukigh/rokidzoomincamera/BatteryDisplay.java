package io.github.ksuzukigh.rokidzoomincamera;

final class BatteryDisplay {
    private BatteryDisplay() {}

    static int percentage(int level, int scale) {
        if (level < 0 || scale <= 0) return -1;
        return Math.max(0, Math.min(100, Math.round(level * 100f / scale)));
    }

    static String label(int percent, boolean charging) {
        if (percent < 0) return "Бат. --%";
        if (charging) return "Заряд " + percent + "%";
        if (percent <= 10) return "Зарядите " + percent + "%";
        if (percent <= 20) return "Мало " + percent + "%";
        return "Бат. " + percent + "%";
    }
}
