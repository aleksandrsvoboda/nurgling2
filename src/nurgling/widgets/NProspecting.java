package nurgling.widgets;

import haven.*;
import haven.render.*;
import haven.res.ui.tt.q.quality.Quality;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.i18n.L10n;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.tools.DirectionalVector;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static haven.MCache.tilesz;
import static java.lang.Math.*;

public class NProspecting extends Window {
    private static final int MAXPEND = 8;
    private static final Pattern detect = Pattern.compile("There appears to be (.*) directly below.");

    /** The three pieces of a prospecting result arrive independently: the quality from the
     *  flower-menu action, the window from the server, and the dowse effect as a gob overlay on
     *  the loader thread. They are queued here until they can be paired up.
     *
     *  This is held by NGameUI rather than statically, so that a window belonging to one session
     *  can never be handed an effect produced by another one - drawing it means calling into that
     *  session's map view - and so that the queues die with the session they belong to. */
    public static final class Pending {
        private final Queue<NProspecting> windows = new ArrayDeque<>();
        private final Queue<Dowse> effects = new ArrayDeque<>();
        private final Queue<Quality> qualities = new ArrayDeque<>();
    }

    private Pending pend;
    private final Object slotlock = new Object();
    private RenderTree.Slot slot;
    private boolean disposed = false;
    private Coord2d pc;
    private String detected;
    private final Button mark;

    public NProspecting(Coord sz, String cap) {
        super(sz, cap);
        mark = add(new Button(UI.scale(100), L10n.get("prospect.mark"), false), UI.scale(105, 25));
        mark.action(this::mark);
        mark.hide();
    }

    @Override
    public void pack() {
        mark.c.y = children(Button.class).stream().filter(button -> button != mark).findFirst().orElse(mark).c.y;
        super.pack();
    }

    /* Cleanup lives in dispose() rather than destroy(): when an ancestor widget goes away
     * (logout, character switch, session close) the children only get rdispose()'d, so a
     * destroy()-based cleanup would leave this window registered with a dead map view behind
     * it. */
    @Override
    public void dispose() {
        Pending p = pend;
        if(p != null) {
            synchronized (p) {p.windows.remove(this);}
        }
        RenderTree.Slot old;
        synchronized (slotlock) {
            disposed = true;
            old = slot;
            slot = null;
        }
        if(old != null) {
            try {
                old.remove();
            } catch(RenderTree.SlotRemoved e) {
                /* The whole render tree is already gone. */
            }
        }
        super.dispose();
    }

    @Override
    protected void attach(UI ui) {
        super.attach(ui);
        Pending p = (ui.gui == null) ? null : ui.gui.prospecting;
        if(p != null) {
            pend = p;
            synchronized (p) {
                p.windows.add(this);
                trim(p.windows);
            }
        }
        Gob player = ((ui.gui == null) || (ui.gui.map == null)) ? null : ui.gui.map.player();
        pc = (player == null) ? null : player.rc;
        if(p != null) {attachEffect(p);}
    }

    private void mark() {
        if(detected == null) {return;}
        if(pc == null) {
            Gob p = ui.gui.map.player();
            if(p != null) {pc = p.rc;}
        }
//        if(pc != null) {
//            ui.gui.mapfile.addMarker(pc.floor(tilesz), String.format("%s (below)", NUtils.prettyResName(detected)));
//        }
    }

    /**
     * Attaches the effect to this window's map view.
     * @return false if the window can no longer draw, in which case the caller must try another one.
     */
    private boolean fx(Dowse fx) {
        NGameUI gui = (ui == null) ? null : ui.gui;
        if((gui == null) || (gui.map == null)) {return false;}
        synchronized (slotlock) {
            if(disposed) {return false;}
            RenderTree.Slot nslot;
            try {
                nslot = gui.map.drawadd(fx);
            } catch(RenderTree.SlotRemoved e) {
                /* The map view was disposed while the effect was in flight from the loader
                 * thread. Nothing to draw on anymore. */
                return false;
            }
            if(slot != null) {slot.remove();}
            slot = nslot;
        }
        return true;
    }

    private static void trim(Queue<?> queue) {
        while(queue.size() > MAXPEND) {queue.remove();}
    }

    private static void attachEffect(Pending p) {
        synchronized (p) {
            while(!p.windows.isEmpty() && !p.effects.isEmpty()) {
                if(p.windows.remove().fx(p.effects.peek())) {
                    p.effects.remove();
                    return;
                }
                /* That window is gone; keep the effect and try the next one. */
            }
        }
    }

    public static void overlay(Gob gob, Gob.Overlay overlay) {
        /* The gob's own session, not the rendered one: this runs on the loader thread, which
         * carries no session binding. */
        NGameUI gui = ownerGui(gob);
        if(gui == null) {return;}
        Pending p = gui.prospecting;
        Quality q;
        synchronized (p) {
            if(p.qualities.isEmpty()) {return;}
            q = p.qualities.remove();
        }

        double a1 = getFieldValueDouble(overlay.spr, "a1");
        double a2 = getFieldValueDouble(overlay.spr, "a2");

        synchronized (p) {
            p.effects.add(new Dowse(gob, a1, a2, q));
            trim(p.effects);
        }
        attachEffect(p);

        // Add directional vectors for cone edges
        addConeVectors(gob, a1, a2);
    }

    /**
     * Adds directional vectors for the edges of a dowsing/tracking cone to the minimap.
     * @param gob The player gob (origin of the cone)
     * @param a1 First angle (left edge)
     * @param a2 Second angle (right edge)
     */
    public static void addConeVectors(Gob gob, double a1, double a2) {
        try {
            // Check if tracking vectors feature is enabled
            Object trackingVectorsSetting = NConfig.get(NConfig.Key.trackingVectors);
            if (!(trackingVectorsSetting instanceof Boolean) || !(Boolean) trackingVectorsSetting) {
                return;
            }

            /* The gob's own session, not the rendered one: this runs on the loader thread,
             * which carries no session binding. */
            NGameUI gui = ownerGui(gob);
            if (gui == null || !(gui.map instanceof NMapView)) {
                return;
            }
            if (gui.mmap == null || gui.mmap.sessloc == null) {
                return;
            }

            NMapView mapView = (NMapView) gui.map;
            MiniMap.Location sessloc = gui.mmap.sessloc;

            // Player position in world coordinates
            Coord2d playerWorld = gob.rc;

            // Convert player world position to tile coordinates
            Coord playerTileCoords = playerWorld.div(MCache.tilesz).floor().add(sessloc.tc);

            // Calculate far points along each edge of the cone
            // Use a long distance so the vectors extend far on the map
            double vectorLength = 5000; // In world units

            // Edge 1: angle a1
            // Note: The game uses a coordinate system where Y is inverted for rendering
            // cos(a) gives X direction, sin(a) gives Y direction (but we need to check the sign)
            Coord2d edge1World = new Coord2d(
                playerWorld.x + cos(a1) * vectorLength,
                playerWorld.y - sin(a1) * vectorLength  // Negate sin because of coordinate system
            );
            Coord edge1TileCoords = edge1World.div(MCache.tilesz).floor().add(sessloc.tc);

            // Edge 2: angle a2
            Coord2d edge2World = new Coord2d(
                playerWorld.x + cos(a2) * vectorLength,
                playerWorld.y - sin(a2) * vectorLength  // Negate sin because of coordinate system
            );
            Coord edge2TileCoords = edge2World.div(MCache.tilesz).floor().add(sessloc.tc);

            // Get color for this pair (same color for both edges)
            java.awt.Color pairColor = DirectionalVector.getNextColor();

            // Add the vectors with the same color
            mapView.directionalVectors.add(new DirectionalVector(
                playerTileCoords, edge1TileCoords, "Dowse Edge 1", -1, pairColor
            ));
            mapView.directionalVectors.add(new DirectionalVector(
                playerTileCoords, edge2TileCoords, "Dowse Edge 2", -1, pairColor
            ));

            // Defer window creation to UI thread to avoid deadlock
            gui.ui.loader.defer(() -> TrackingVectorWindow.showWindow(), null);

        } catch (RuntimeException e) {
            /* This runs on the loader thread, where an escaping exception kills the thread. */
            new Warning(e, "failed to add prospecting cone vectors").issue();
        }
    }

    /** The game UI of the session the gob actually belongs to. */
    private static NGameUI ownerGui(Gob gob) {
        if((gob == null) || (gob.glob == null) || (gob.glob.sess == null)) {return null;}
        SessionContext ctx = SessionManager.getInstance().findBySession(gob.glob.sess);
        return (ctx == null) ? null : ctx.getGameUI();
    }

    public static double getFieldValueDouble(Object obj, String name) {
        double v = 0;
        try {
            Field f = getField(obj, name);
            v = f.getDouble(obj);
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
        }
        return v;
    }

    private static Field getField(Object obj, String name) throws NoSuchFieldException {
        Class cls = obj.getClass();
        while (true) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
                if(cls == null){
                    throw e;
                }
            }

        }
    }

    public static void item(UI ui, WItem item) {
        if((ui == null) || (ui.gui == null) || (item == null)) {return;}
        Pending p = ui.gui.prospecting;
        for(ItemInfo itemInfo: item.item.info)
        {
            if(itemInfo instanceof Quality)
            {
                synchronized (p) {
                    p.qualities.add((Quality)itemInfo);
                    trim(p.qualities);
                }
            }
        }
    }

    public void text(String text) {
        Matcher matcher = detect.matcher(text);
        if(matcher.matches()) {
            detected = matcher.group(1);
        } else if(mark != null) {
            mark.hide();
        }
    }

    public static class Dowse extends Sprite {
        private static final VertexArray.Layout fmt = new VertexArray.Layout(
                new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32), 0, 0, 16),
                new VertexArray.Layout.Input(VertexColor.color, new VectorFormat(4, NumberFormat.UNORM8), 0, 12, 16)
        );
        private static final Pipe.Op state = Pipe.Op.compose(VertexColor.instance, Rendered.last, States.Depthtest.none, States.maskdepth, Rendered.postpfx);

        private final Coord3f c;
        private final double a1;
        private final double a2;
        private final double r;
        private final Model model;

        protected Dowse(Gob gob, double a1, double a2, Quality q) {
            super(gob, null);
            this.c = new Coord3f((float) gob.rc.x, (float) -gob.rc.y, 0.1f);
            this.a1 = a1;
            this.a2 = a2;
            if(q == null) {
                r = 100;
            } else {
                r = 110 * (q.q - 10);
            }
            model = new Model(Model.Mode.TRIANGLE_FAN, new VertexArray(fmt, new VertexArray.Buffer(v2(), DataBuffer.Usage.STREAM)), null);
        }

        private ByteBuffer v2() {
            ByteBuffer buf = ByteBuffer.allocate(128);
            buf.order(ByteOrder.nativeOrder());
            byte alpha = (byte) 80;

            buf.putFloat(c.x).putFloat(c.y).putFloat(c.z);
            buf.put((byte) 255).put((byte) 0).put((byte) 0).put(alpha);
            for (double ca = a1; ca < a2; ca += PI * 0x0.04p0) {
                buf = Utils.growbuf(buf, 16);
                buf.putFloat(c.x + (float) (cos(ca) * r)).putFloat(c.y + (float) (sin(ca) * r)).putFloat(c.z);
                buf.put((byte) 255).put((byte) 0).put((byte) 0).put(alpha);
            }
            buf = Utils.growbuf(buf, 16);
            buf.putFloat(c.x + (float) (cos(a2) * r)).putFloat(c.y + (float) (sin(a2) * r)).putFloat(c.z);
            buf.put((byte) 255).put((byte) 0).put((byte) 0).put(alpha);
            ((Buffer) buf).flip();
            return (buf);
        }


        public void added(RenderTree.Slot slot) {
            slot.ostate(state);
            slot.add(model);
        }
    }
}
