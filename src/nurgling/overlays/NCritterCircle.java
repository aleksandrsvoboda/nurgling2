package nurgling.overlays;

import haven.*;
import haven.render.*;
import haven.render.Model.Indices;
import nurgling.NConfig;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.awt.*;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Set;

import static haven.MCache.tilesz;

/**
 * Draws a flat colored circle on the ground under small critters
 * to make them easier to click. The circle follows terrain height.
 */
public class NCritterCircle extends Sprite {

    private static final float OUTER_RADIUS = 10f;
    private static final float HEIGHT = 0.45f;
    private static final int VERTEX_COUNT = 256; // 4 * 64

    private static final Color CRITTER_COLOR = new Color(193, 0, 255, 140);
    private static final Color RABBIT_COLOR = new Color(88, 255, 0, 140);

    private static final Pipe.Op EDGE_MAT = Pipe.Op.compose(
            new BaseColor(new Color(0, 0, 0, 200)),
            new States.LineWidth(2)
    );

    private static final NAlias DEAD_KNOCKED = new NAlias("dead", "knock");

    /**
     * Small critters that are hard to click and benefit from a ground circle.
     * Does NOT include large animals (cattle, boar, etc.) which use NAreaRange.
     */
    private static final Set<String> CRITTER_PATHS = Set.of(
            "gfx/kritter/bayshrimp/bayshrimp",
            "gfx/kritter/bogturtle/bogturtle",
            "gfx/kritter/brimstonebutterfly/brimstonebutterfly",
            "gfx/kritter/cavecentipede/cavecentipede",
            "gfx/kritter/cavemoth/cavemoth",
            "gfx/kritter/chicken/chick",
            "gfx/kritter/chicken/chicken",
            "gfx/kritter/chicken/hen",
            "gfx/kritter/chicken/rooster",
            "gfx/kritter/crab/crab",
            "gfx/kritter/dragonfly/dragonfly",
            "gfx/kritter/earthworm/earthworm",
            "gfx/kritter/firefly/firefly",
            "gfx/kritter/forestlizard/forestlizard",
            "gfx/kritter/forestsnail/forestsnail",
            "gfx/kritter/frog/frog",
            "gfx/kritter/grasshopper/grasshopper",
            "gfx/kritter/hedgehog/hedgehog",
            "gfx/kritter/irrbloss/irrbloss",
            "gfx/kritter/jellyfish/jellyfish",
            "gfx/kritter/ladybug/ladybug",
            "gfx/kritter/lobster/lobster",
            "gfx/kritter/magpie/magpie",
            "gfx/kritter/mallard/mallard",
            "gfx/kritter/mallard/mallard-f",
            "gfx/kritter/mallard/mallard-m",
            "gfx/kritter/mole/mole",
            "gfx/kritter/monarchbutterfly/monarchbutterfly",
            "gfx/kritter/moonmoth/moonmoth",
            "gfx/kritter/opiumdragon/opiumdragon",
            "gfx/kritter/ptarmigan/ptarmigan",
            "gfx/kritter/quail/quail",
            "gfx/kritter/rabbit/rabbit",
            "gfx/kritter/rat/rat",
            "gfx/kritter/rockdove/rockdove",
            "gfx/kritter/sandflea/sandflea",
            "gfx/kritter/seagull/seagull",
            "gfx/kritter/silkmoth/silkmoth",
            "gfx/kritter/springbumblebee/springbumblebee",
            "gfx/kritter/squirrel/squirrel",
            "gfx/kritter/stagbeetle/stagbeetle",
            "gfx/kritter/stalagoomba/stalagoomba",
            "gfx/kritter/tick/tick",
            "gfx/kritter/tick/tick-bloated",
            "gfx/kritter/toad/toad",
            "gfx/kritter/waterstrider/waterstrider",
            "gfx/kritter/woodgrouse/woodgrouse-f",
            "gfx/kritter/woodworm/woodworm",
            "gfx/kritter/whirlingsnowflake/whirlingsnowflake",
            "gfx/kritter/bullfinch/bullfinch",
            "gfx/terobjs/items/grub",
            "gfx/terobjs/items/hoppedcow",
            "gfx/terobjs/items/mandrakespirited",
            "gfx/terobjs/items/itsybitsyspider"
    );

    private final VertexBuf.VertexData posa;
    private final VertexBuf vbuf;
    private final Model smod, emod;
    private final Pipe.Op fillMat;
    private Coord2d lc;
    private boolean visible;

    public NCritterCircle(Owner owner, Color color) {
        super(owner, null);
        this.fillMat = new BaseColor(color);
        this.visible = isEnabled();

        FloatBuffer posb = Utils.wfbuf(VERTEX_COUNT * 3 * 2);
        FloatBuffer nrmb = Utils.wfbuf(VERTEX_COUNT * 3 * 2);
        double step = Math.PI / 64;
        double rad = 0;
        for (int i = 0; i < VERTEX_COUNT; i++) {
            float angx = (float) Math.cos(rad);
            float angy = (float) Math.sin(rad);
            float ox = angx * OUTER_RADIUS;
            float oy = angy * OUTER_RADIUS;
            // Outer ring
            posb.put(i * 3, ox).put(i * 3 + 1, oy).put(i * 3 + 2, HEIGHT);
            // Inner ring (center point for filled disc)
            posb.put((VERTEX_COUNT + i) * 3, 0).put((VERTEX_COUNT + i) * 3 + 1, 0).put((VERTEX_COUNT + i) * 3 + 2, HEIGHT);
            // Normals pointing up
            nrmb.put(i * 3, 0).put(i * 3 + 1, 0).put(i * 3 + 2, 1);
            nrmb.put((VERTEX_COUNT + i) * 3, 0).put((VERTEX_COUNT + i) * 3 + 1, 0).put((VERTEX_COUNT + i) * 3 + 2, 1);
            rad += step;
        }
        posa = new VertexBuf.VertexData(posb);
        VertexBuf.NormalData nrma = new VertexBuf.NormalData(nrmb);
        vbuf = new VertexBuf(posa, nrma);
        smod = new Model(Model.Mode.TRIANGLES, vbuf.data(),
                new Indices(VERTEX_COUNT * 3, NumberFormat.UINT16, DataBuffer.Usage.STATIC, this::sidx));
        emod = new Model(Model.Mode.LINE_STRIP, vbuf.data(),
                new Indices(VERTEX_COUNT + 1, NumberFormat.UINT16, DataBuffer.Usage.STATIC, this::eidx));
    }

    private FillBuffer sidx(Indices dst, Environment env) {
        FillBuffer ret = env.fillbuf(dst);
        ShortBuffer buf = ret.push().asShortBuffer();
        int n = VERTEX_COUNT;
        for (int i = 0; i < n; i++) {
            int b = i * 3;
            buf.put(b, (short) i);
            buf.put(b + 1, (short) (i + n));
            buf.put(b + 2, (short) ((i + 1) % n));
        }
        return ret;
    }

    private FillBuffer eidx(Indices dst, Environment env) {
        FillBuffer ret = env.fillbuf(dst);
        ShortBuffer buf = ret.push().asShortBuffer();
        for (int i = 0; i < VERTEX_COUNT; i++)
            buf.put(i, (short) i);
        buf.put(VERTEX_COUNT, (short) 0);
        return ret;
    }

    private void setz(Render g, Glob glob, Coord2d c) {
        FloatBuffer posb = posa.data;
        int n = VERTEX_COUNT;
        try {
            float bz = (float) glob.map.getcz(c.x, c.y);
            for (int i = 0; i < n; i++) {
                float z = (float) glob.map.getcz(c.x + posb.get(i * 3), c.y - posb.get(i * 3 + 1)) - bz;
                posb.put(i * 3 + 2, z + HEIGHT);
                // Inner ring vertices are at center, same base z
                posb.put((n + i) * 3 + 2, HEIGHT);
            }
        } catch (Loading e) {
            return;
        }
        vbuf.update(g);
    }

    @Override
    public void gtick(Render g) {
        boolean enabled = isEnabled();
        if (visible != enabled) {
            visible = enabled;
        }
        if (!visible)
            return;
        Coord2d cc = ((Gob) owner).rc;
        if (lc == null || !lc.equals(cc)) {
            setz(g, owner.context(Glob.class), cc);
            lc = cc;
        }
    }

    @Override
    public void added(RenderTree.Slot slot) {
        slot.ostate(Pipe.Op.compose(
                Rendered.postpfx,
                new States.Facecull(States.Facecull.Mode.NONE),
                Location.goback("gobx")
        ));
        slot.add(smod, fillMat);
        slot.add(emod, EDGE_MAT);
    }

    @Override
    public boolean tick(double dt) {
        if (!isEnabled())
            return false;
        String pose = ((Gob) owner).pose();
        if (pose != null && NParser.checkName(pose, DEAD_KNOCKED))
            return true;
        return super.tick(dt);
    }

    public static boolean isCritter(String resName) {
        if (resName == null) return false;
        if (CRITTER_PATHS.contains(resName)) return true;
        // Also match rabbits/bunnies by pattern
        return resName.matches(".*/(rabbit|bunny)$");
    }

    public static boolean isRabbit(String resName) {
        if (resName == null) return false;
        return resName.matches(".*/(rabbit|bunny)$");
    }

    public static Color getColorForCritter(String resName) {
        return isRabbit(resName) ? RABBIT_COLOR : CRITTER_COLOR;
    }

    private static boolean isEnabled() {
        Object val = NConfig.get(NConfig.Key.showCritterCircles);
        return val instanceof Boolean ? (Boolean) val : true;
    }
}
