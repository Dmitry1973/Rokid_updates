package kz.rokid.horizon;

/**
 * Pure math: pitch and roll from the "up" vector (world up expressed in device
 * coordinates). Android sensor axes for a device in its natural orientation:
 * X to the right, Y up along the display, Z out of the display towards the eye.
 * The wearer looks along -Z.
 */
final class Attitude {
    private Attitude() {}

    /** Pitch in degrees, nose up positive: angle of the look direction (-Z) above the horizon. */
    static double pitchDeg(double ux, double uy, double uz) {
        double n = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (n < 1e-9) return 0;
        double s = -uz / n;
        if (s > 1) s = 1;
        if (s < -1) s = -1;
        return Math.toDegrees(Math.asin(s));
    }

    /** Roll in degrees, right ear down positive. */
    static double rollDeg(double ux, double uy, double uz) {
        if (Math.abs(ux) < 1e-9 && Math.abs(uy) < 1e-9) return 0;
        return Math.toDegrees(Math.atan2(-ux, uy));
    }

    /** Wraps an angle into (-180, 180]. */
    static double wrap180(double a) {
        while (a > 180) a -= 360;
        while (a <= -180) a += 360;
        return a;
    }
}
