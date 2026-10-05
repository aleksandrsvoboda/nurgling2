package nurgling.navigation;

/** World coordinates have south-positive Y; camera angles use render coordinates. */
public final class CompassBearing {
    private CompassBearing() {}

    public static double wrap(double radians) {
        return Math.atan2(Math.sin(radians), Math.cos(radians));
    }

    public static double heading(double cameraAngle) {
        return wrap(Math.PI - cameraAngle);
    }

    /** A full circle, with the direction behind the camera at either edge. */
    public static double fraction(double bearing, double cameraAngle) {
        return 0.5 + wrap(bearing - heading(cameraAngle)) / (2 * Math.PI);
    }
}
