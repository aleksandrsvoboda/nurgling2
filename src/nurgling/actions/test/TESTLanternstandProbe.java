package nurgling.actions.test;

import haven.*;
import haven.res.gfx.fx.eq.Equed;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;

/**
 * Diagnostic: dumps everything the client knows about each Lantern Stand in view. Confirmed in
 * game: empty = no gfx/fx/eq overlay; a hung lantern = eq overlay on the stand's "h" whose sub
 * sprite is another eq holding gfx/terobjs/items/lantern; that one's flags bit 0 = lit. Output goes to stdout and to
 * lanternstand_probe.txt in the client data dir.
 */
public class TESTLanternstandProbe implements Action {
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        ArrayList<Gob> stands = Finder.findGobs(new NAlias("gfx/terobjs/lanternstand"));
        StringBuilder out = new StringBuilder();
        out.append("=== Lanternstand probe ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()))
           .append(" : ").append(stands.size()).append(" stand(s)\n");
        Gob player = NUtils.player();
        for (Gob g : stands) {
            out.append(describe(g, player));
            gui.msg("Lanternstand " + g.id + ": " + verdict(g));
        }
        if (stands.isEmpty())
            gui.msg("No lanternstand in view");
        String text = out.toString();
        System.out.print(text);
        Path file = NUtils.getDataFilePath("lanternstand_probe.txt");
        try {
            Files.write(file, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            gui.msg("Probe written to " + file);
        } catch (IOException e) {
            gui.msg("Could not write " + file + ": " + e.getMessage());
        }
        return Results.SUCCESS();
    }

    private static String describe(Gob g, Gob player) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- gob ").append(g.id).append(" name=").append(g.ngob.name);
        if (player != null)
            sb.append(" dist=").append(String.format("%.1f", g.rc.dist(player.rc)));
        sb.append(" modelAttr=").append(g.ngob.getModelAttribute()).append('\n');
        sb.append("   verdict: ").append(verdict(g)).append('\n');

        synchronized (g) {
            for (Map.Entry<Class<? extends GAttrib>, GAttrib> e : g.attr.entrySet()) {
                GAttrib a = e.getValue();
                sb.append("   attr ").append(e.getKey().getSimpleName()).append(" = ").append(a.getClass().getName());
                if (a instanceof ResDrawable) {
                    ResDrawable rd = (ResDrawable) a;
                    sb.append(" res=").append(rd.res != null && rd.res.get() != null ? rd.res.get().name : "?");
                    sb.append(" sdt=").append(bytes(rd.sdt == null ? new byte[0] : rd.sdt.clone().bytes()));
                    if (rd.spr != null)
                        sb.append(" spr=").append(rd.spr.getClass().getName());
                }
                sb.append('\n');
            }
            for (Gob.Overlay ol : g.ols) {
                sb.append("   overlay id=").append(ol.id);
                sb.append(" spr=").append(ol.spr == null ? "null" : ol.spr.getClass().getName());
                sb.append(" sprRes=").append(ol.spr != null && ol.spr.res != null ? ol.spr.res.name : "null");
                if (ol.sm instanceof OCache.OlSprite) {
                    OCache.OlSprite os = (OCache.OlSprite) ol.sm;
                    sb.append(" olRes=").append(resName(os.res));
                    sb.append(" sdt=").append(bytes(os.sdt));
                    if (ol.spr instanceof Equed)
                        sb.append(" eq=").append(decodeEq(os.sdt));
                }
                Sprite espr = espr(ol);
                if (espr != null)
                    sb.append(" espr=").append(espr.getClass().getName())
                      .append(" esprRes=").append(espr.res != null ? espr.res.name : "null");
                sb.append('\n');
            }
            if (g.ols.isEmpty())
                sb.append("   (no overlays)\n");
        }
        return sb.toString();
    }

    /**
     * The lantern is an eq inside an eq: outer eq hangs on the stand's "h", its sub-sprite is a
     * second eq carrying the lantern res, whose own sub flags hold bit 0 = lit. Walk the Equed
     * chain and its sdt chain together down to the hung item.
     */
    private static String verdict(Gob g) {
        for (Gob.Overlay ol : g.ols) {
            if (ol.spr instanceof Equed && ol.sm instanceof OCache.OlSprite) {
                Sprite spr = ol.spr;
                byte[] sdt = ((OCache.OlSprite) ol.sm).sdt;
                while (spr instanceof Equed && sdt != null) {
                    spr = espr(spr);
                    sdt = subSdt(sdt);
                }
                String r = spr != null && spr.res != null ? spr.res.name : "?";
                int fl = sdt == null ? 0 : Sprite.decnum(new MessageBuf(sdt));
                return "hanging " + r + (((fl & 1) != 0) ? " LIT" : " unlit") + " (itemflags=" + fl + ")";
            }
        }
        return "EMPTY (no Equed overlay)";
    }

    /** gfx/fx/eq sdt: uint16 res id, uint8 fl, string bone, [fl&2: uint8 len, sub sdt]. */
    private static String decodeEq(byte[] sdt) {
        MessageBuf m = new MessageBuf(sdt);
        int resid = m.uint16();
        int fl = m.uint8();
        String bone = m.string();
        StringBuilder sb = new StringBuilder();
        sb.append("{resid=").append(resid).append(" fl=").append(fl).append(" bone=").append(bone);
        if ((fl & 2) != 0) {
            byte[] sub = m.bytes(m.uint8());
            sb.append(" sub=").append(bytes(sub));
            if (sub.length > 3 && (sub[2] & 0xFF) <= 3)
                sb.append(" subAsEq=").append(decodeEq(sub));
        } else {
            sb.append(" no sub");
        }
        if (!m.eom())
            sb.append(" trailing=").append(bytes(m.bytes()));
        return sb.append('}').toString();
    }

    /** The sub-sprite sdt of an eq sdt, or null when it carries none. */
    private static byte[] subSdt(byte[] sdt) {
        MessageBuf m = new MessageBuf(sdt);
        m.uint16();
        int fl = m.uint8();
        m.string();
        return ((fl & 2) == 0) ? null : m.bytes(m.uint8());
    }

    private static Sprite espr(Gob.Overlay ol) {
        return espr(ol.spr);
    }

    private static Sprite espr(Sprite eq) {
        if (!(eq instanceof Equed))
            return null;
        try {
            Field f = Equed.class.getDeclaredField("espr");
            f.setAccessible(true);
            return (Sprite) f.get(eq);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }

    private static String resName(Indir<Resource> r) {
        try {
            return r.get().name;
        } catch (Loading l) {
            return "(loading)";
        }
    }

    private static String bytes(byte[] b) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(b[i] & 0xFF);
        }
        return sb.append(']').toString();
    }
}
