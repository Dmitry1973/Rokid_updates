package kz.rokid.horizon;

/**
 * Pure math for the attitude display. Android sensor axes for a device in its
 * natural orientation: X to the right, Y up along the display, Z out of the
 * display towards the eye. The wearer looks along -Z.
 * "up" = world up expressed in device coordinates (unit vector).
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

    /** Roll (bank) in degrees, right ear down positive. */
    static double rollDeg(double ux, double uy, double uz) {
        if (Math.abs(ux) < 1e-9 && Math.abs(uy) < 1e-9) return 0;
        return Math.toDegrees(Math.atan2(-ux, uy));
    }

    /**
     * Heading of the look direction in degrees 0..360 from a rotation matrix
     * (device to world, row-major, world X = east, Y = north).
     */
    static double headingDeg(float[] r) {
        // device -Z axis in world coordinates = -(third column)
        double east = -r[2];
        double north = -r[5];
        if (Math.abs(east) < 1e-6 && Math.abs(north) < 1e-6) return Double.NaN;
        double h = Math.toDegrees(Math.atan2(east, north));
        return h < 0 ? h + 360 : h;
    }

    /**
     * Slip / skid in degrees from the specific force (accelerometer, device frame):
     * angle of the apparent gravity in the X-Y plane. 0 = ball centred; positive =
     * ball to the right.
     */
    static double slipDeg(double ax, double ay) {
        if (Math.abs(ax) < 1e-9 && Math.abs(ay) < 1e-9) return 0;
        return Math.toDegrees(Math.atan2(-ax, ay));
    }

    /** Turn rate in deg/s, right turn positive: gyro rate about world up. */
    static double turnRateDeg(double wx, double wy, double wz, double ux, double uy, double uz) {
        return -Math.toDegrees(wx * ux + wy * uy + wz * uz);
    }

    /** Pressure altitude in metres for the given pressure and reference (QNH / 1013.25). */
    static double pressureAltitudeM(double hpa, double refHpa) {
        if (hpa <= 0 || refHpa <= 0) return Double.NaN;
        return 44330.0 * (1.0 - Math.pow(hpa / refHpa, 1.0 / 5.255));
    }

    /** Wraps an angle into (-180, 180]. */
    static double wrap180(double a) {
        while (a > 180) a -= 360;
        while (a <= -180) a += 360;
        return a;
    }
}
