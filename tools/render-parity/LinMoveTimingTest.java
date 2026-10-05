package haven;

import java.util.function.DoubleSupplier;

/** Deterministic replay: a correction during a stalled frame must not count its dt twice. */
public class LinMoveTimingTest {
    static class Clock implements DoubleSupplier {
        double time;
        public double getAsDouble() { return time; }
    }
    static LinMove movement(Clock c) { return new LinMove(null, Coord2d.z, Coord2d.of(80, 0), c); }
    static void near(double actual, double expected, String message) {
        if(Math.abs(actual - expected) > 1e-9)
            throw new AssertionError(message + ": " + actual + " != " + expected);
    }
    public static void main(String[] args) {
        Clock c = new Clock(); LinMove m = movement(c);
        m.t = .308549505;
        c.time = .049504; m.sett(.3603515625);
        c.time = .0505161; m.ctick(.0505161);
        near(m.t, .3612624525, "OpenGL 50ms stall replay");
        // Old code produced .4058160525, adding the whole frame after the correction.
        c = new Clock(); m = movement(c); m.t = .06030027;
        c.time = .059; m.sett(.1201171875);
        c.time = .09773; m.ctick(.09773);
        near(m.t, .1201171875 + .03873 * .9, "Vulkan stall replay");

        c = new Clock(); m = movement(c);
        c.time = .1; m.sett(.02); // Behind current extrapolation: keep elapsed motion.
        c.time = .12; m.ctick(.12);
        near(m.t, .108, "Delayed update must not slow or repeat prediction");
        c.time = .13; m.sett(.2); m.sett(.2);
        c.time = .14; m.ctick(.02);
        near(m.t, .209, "Repeated updates at one instant");

        c = new Clock(); c.time = 10; m = movement(c);
        c.time = 10.002; m.ctick(.2);
        near(m.t, .0018, "New trajectory cannot inherit time before its creation");
        c.time = 9; m.ctick(.2);
        near(m.t, .0018, "Clock regression");

        c = new Clock(); m = movement(c);
        c.time = 2; m.ctick(2); near(m.t, LinMove.MAXOVER, "Prediction horizon");
        c.time = 3; m.ctick(1); near(m.t, LinMove.MAXOVER, "Stopped prediction");
        m.sett(.6); c.time = 3.01; m.ctick(1);
        near(m.t, .609, "Resume without accumulating stopped time");
        m.e = .62; c.time = 3.1; m.ctick(.09); near(m.t, .62, "Authoritative end");

        // Same network timeline, different frame cadence, same final prediction.
        Clock a = new Clock(), b = new Clock(); LinMove fast = movement(a), slow = movement(b);
        for(int i = 1; i <= 100; i++) {
            a.time = b.time = i * .01;
            if(i % 12 == 0) { fast.sett(i * .01); slow.sett(i * .01); }
            fast.ctick(.01);
            if(i % 10 == 0) slow.ctick(.1);
        }
        near(fast.t, slow.t, "Prediction depends on frame cadence");
        System.out.println("LinMove timing PASS: recorded stalls, delayed/repeated updates, trajectory start, horizon, resume, end and cadence");
    }
}
