package haven;

/** Replays the captured back/forward corrections without a renderer or server. */
public class MovementSmoothingTest {
    static class TestGob extends Gob {
        TestGob(long id) { super(null, Coord2d.z, id); ngob.effector = true; }
        // No live world/overlays: exercise the real movement, placement and tick paths.
        public void setattr(Class<? extends GAttrib> key, GAttrib value) {
            if(value == null) attr.remove(key); else attr.put(key, value);
        }
        public Placer placer() {
            return new Placer() {
                public Coord3f getc(Coord2d p, double a) { return new Coord3f((float)p.x, (float)p.y, 0); }
                public Matrix4f getr(Coord2d p, double a) { return Matrix4f.id; }
            };
        }
        protected haven.render.Pipe.Op getmapstate(Coord3f p) { return null; }
    }
    static void begin(Gob gob, int x, int velocity) {
        MessageBuf msg = new MessageBuf();
        msg.addcoord(new Coord((int)Math.round(x / OCache.posres.x), 0));
        msg.addcoord(new Coord((int)Math.round(velocity / OCache.posres.x), 0));
        new LinMove.$linbeg().apply(gob, new OCache.AttrDelta(new OCache.ObjDelta(), OCache.OD_LINBEG, msg.fin()));
    }
    static void integration() {
        nurgling.NConfig.getGlobalInstance(); // In-memory defaults, no user preference writes.
        TestGob mount = new TestGob(1), rider = new TestGob(2);
        begin(mount, 0, 0);
        mount.ctick(0);
        mount.placed.autotick(0);
        Coord2d frame = Coord2d.of(mount.placed.getc());
        rider.setattr(new Following(rider, mount.id, null, null) {
            public Gob tgt() { return mount; }
            public haven.render.Pipe.Op xf() { return mount.placed.placement(); }
        });
        begin(mount, -18, 100);
        near(Coord2d.of(mount.getrenderc()), frame, "New trajectory leaked into scene");
        near(Coord2d.of(rider.getrenderc()), frame, "Rider camera ignored mount snapshot");
        MessageBuf step = new MessageBuf(); step.addint32(123);
        new LinMove.$linstep().apply(mount, new OCache.AttrDelta(new OCache.ObjDelta(), OCache.OD_LINSTEP, step.fin()));
        near(Coord2d.of(mount.getrenderc()), frame, "Server step changed published coordinates");
        mount.placed.autotick(0);
        near(Coord2d.of(mount.placed.getc()), frame, "Placement bypassed render reconciliation");
        require(Coord2d.of(mount.getc()).dist(frame) > 1, "Gameplay coordinates were smoothed");
        mount.ctick(0); mount.placed.autotick(0);
        near(Coord2d.of(mount.placed.getc()), Coord2d.of(rider.getrenderc()), "Scene and mounted camera disagree after tick");
        frame = Coord2d.of(mount.getrenderc());
        MessageBuf stop = new MessageBuf(); stop.addint32(-1);
        new LinMove.$linstep().apply(mount, new OCache.AttrDelta(new OCache.ObjDelta(), OCache.OD_LINSTEP, stop.fin()));
        require(mount.getattr(Moving.class) == null, "Stop did not remove authoritative movement");
        near(Coord2d.of(mount.getrenderc()), frame, "Stop changed frame already published");
        mount.ctick(0); mount.placed.autotick(0);
        near(Coord2d.of(mount.placed.getc()), Coord2d.of(rider.getrenderc()), "Stopped mount/camera diverged");
        System.out.println("Movement integration PASS: actual linbeg/linstep/stop handlers, Gob tick, placement and mounted camera coordinates");
    }
    static void require(boolean value, String message) {
        if(!value) throw new AssertionError(message);
    }
    static void near(Coord2d a, Coord2d b, String message) {
        require(a.dist(b) < 1e-7, message + ": " + a + " != " + b);
    }
    public static void main(String[] args) {
        MovementSmoothing smooth = new MovementSmoothing();
        Coord2d raw = Coord2d.of(0, 100);
        smooth.correct(Coord2d.z, raw, 0);
        smooth.publish(raw, 0);
        near(smooth.position(), raw, "First appearance must not fly in");
        for(int i = 1; i <= 60; i++) {
            raw = Coord2d.of(0, 100 + i);
            smooth.publish(raw, i / 60.0);
            near(smooth.position(), raw, "Steady motion must have no smoothing lag");
        }

        // Approximate magnitudes and timing from capture 21:57:50:
        // new trajectory starts ~18 units behind; a 0.120s step follows 4ms later.
        smooth = new MovementSmoothing();
        raw = Coord2d.of(0, 0);
        smooth.publish(raw, 0);
        smooth.correct(Coord2d.of(0, .9), Coord2d.of(0, -17.7), .009);
        Coord2d scene = smooth.position();
        near(scene, raw, "linbeg changed this frame's scene position");
        smooth.correct(Coord2d.of(0, -17.3), Coord2d.of(0, -3.9), .013);
        near(smooth.position(), scene, "linstep changed this frame's camera position");
        raw = Coord2d.of(0, -3.6);
        smooth.publish(raw, .016);
        Coord2d first = smooth.position();
        require(first.y > 0 && first.y < 2, "Captured burst caused a backwards jump: " + first);
        raw = raw.add(0, 1.6);
        smooth.publish(raw, .032);
        Coord2d second = smooth.position();
        require(second.y > first.y && second.dist(first) < 3, "Burst catch-up jumped forwards");
        // Another authoritative trajectory starts ~11 units ahead.
        smooth.correct(raw.add(0, 1), raw.add(0, 12), .042);
        near(smooth.position(), second, "Second trajectory leaked into current frame");
        raw = raw.add(0, 12.6);
        smooth.publish(raw, .048);
        require(smooth.position().dist(second) < 4, "Second trajectory caused visible teleport");

        // Stop/end correction settles at the server position, with no residual drift.
        Coord2d stop = raw.add(0, -4);
        smooth.correct(raw, stop, .05);
        smooth.publish(stop, 2);
        near(smooth.position(), stop, "Stopped actor never reached authoritative position");

        // A stopped (idle-posed) actor sheds its residual offset within ~6 frames instead of sliding.
        smooth = new MovementSmoothing(); smooth.publish(Coord2d.z, 0);
        smooth.correct(Coord2d.of(0, -4), Coord2d.z, 0);
        smooth.settleWithin(0);
        for(int i = 1; i <= 6; i++) smooth.publish(Coord2d.z, i / 60.0);
        require(smooth.position().abs() < .3, "Stopped actor kept sliding: " + smooth.position());

        // Exact opposite updates cancel, and large teleports snap instead of sliding.
        smooth = new MovementSmoothing(); smooth.publish(Coord2d.z, 0);
        smooth.correct(Coord2d.z, Coord2d.of(10, 0), .01);
        smooth.correct(Coord2d.of(10, 0), Coord2d.z, .01);
        smooth.publish(Coord2d.z, .02);
        near(smooth.position(), Coord2d.z, "Same-instant correction cancellation");
        smooth.correct(Coord2d.z, Coord2d.of(1000, 0), .03);
        require(smooth.position() == null, "Teleport retained old frame");
        smooth.publish(Coord2d.of(1000, 0), .04);
        near(smooth.position(), Coord2d.of(1000, 0), "Teleport was smoothed");
        smooth.reset(); smooth.publish(Coord2d.of(5, 5), .05);
        near(smooth.position(), Coord2d.of(5, 5), "Movement mode switch retained offset");

        MovementSmoothing fast = new MovementSmoothing(), slow = new MovementSmoothing();
        fast.publish(Coord2d.z, 0); slow.publish(Coord2d.z, 0);
        fast.correct(Coord2d.z, Coord2d.of(10, 0), 0);
        slow.correct(Coord2d.z, Coord2d.of(10, 0), 0);
        for(int i = 1; i <= 30; i++) fast.publish(Coord2d.of(10 + i, 0), i / 100.0);
        slow.publish(Coord2d.of(40, 0), .3);
        near(fast.position(), slow.position(), "Reconciliation depends on FPS");
        System.out.println("Movement smoothing PASS: recorded-size burst, frame stability, steady motion, stop, teleport, reset and cadence");
        if(args.length > 0 && args[0].equals("--integration")) integration();
    }
}
