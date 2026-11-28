package nurgling.actions.test;

import haven.*;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.cheese.CheeseRackMemory;
import nurgling.cheese.CheeseRackReader;
import nurgling.cheese.CheeseRackShelves;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.NUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Diagnostic for the cheese rack panel: per rack in view, its overlays (id, decoded eq bone, tray
 * resource), the tile under it, and the trays of any open rack window with their slots. Answers:
 * does an overlay id survive a relog, which window slot is which shelf, and do tiles tell the
 * place apart. Output goes to stdout and to cheese_rack_probe.txt in the client data dir.
 */
public class TESTCheeseRackProbe implements Action {
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        ArrayList<Gob> racks = Finder.findGobs(new NAlias(CheeseRackShelves.RACK_RES));
        StringBuilder out = new StringBuilder();
        out.append("=== Cheese rack probe ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()))
           .append(" : ").append(racks.size()).append(" rack(s)\n");
        for (Gob g : racks)
            out.append(describe(gui, g));
        out.append(windows(gui));
        if (racks.isEmpty())
            gui.msg("No cheese rack in view");
        String text = out.toString();
        System.out.print(text);
        Path file = NUtils.getDataFilePath("cheese_rack_probe.txt");
        try {
            Files.write(file, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            gui.msg("Probe of " + racks.size() + " rack(s) written to " + file);
        } catch (IOException e) {
            gui.msg("Could not write " + file + ": " + e.getMessage());
        }
        return Results.SUCCESS();
    }

    private static String describe(NGameUI gui, Gob g) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- rack gob ").append(g.id).append(" hash=").append(g.ngob.hash).append(" rc=").append(g.rc)
          .append(" tile=").append(tileName(gui, g)).append('\n');
        for (Gob.Overlay ol : g.ols) {
            sb.append("   overlay id=").append(ol.id)
              .append(" spr=").append(ol.spr == null ? "null" : ol.spr.getClass().getName());
            if (ol.sm instanceof OCache.OlSprite) {
                OCache.OlSprite os = (OCache.OlSprite) ol.sm;
                sb.append(" olRes=").append(resName(os.res)).append(" sdt=").append(bytes(os.sdt));
                CheeseRackShelves.Eq eq = CheeseRackShelves.decodeEq(os.sdt);
                int depth = 0;
                while (eq != null && depth++ < 3) {
                    String r = resName(g.glob.sess.getres(eq.resid));
                    sb.append("\n      eq bone=").append(eq.bone).append(" shelf=").append(CheeseRackShelves.shelfOf(eq.bone))
                      .append(" res=").append(r).append(" tier=").append(CheeseRackShelves.tierOf(r))
                      .append(" sub=").append(eq.sub == null ? "none" : bytes(eq.sub));
                    eq = (CheeseRackShelves.EQ_RES.equals(r) && eq.sub != null) ? CheeseRackShelves.decodeEq(eq.sub) : null;
                }
            }
            sb.append('\n');
        }
        CheeseRackMemory.Seen[] seen = CheeseRackReader.readShelves(g);
        sb.append("   shelves (bottom..top):");
        if (seen == null) {
            sb.append(" loading");
        } else {
            for (CheeseRackMemory.Seen s : seen)
                sb.append(s == null ? " [none]" : " [ol " + s.olId + " tier " + s.tier + "]");
        }
        sb.append('\n');
        return sb.toString();
    }

    /** Every open rack window: its parent gob and each tray's slot and content. */
    private static String windows(NGameUI gui) {
        StringBuilder sb = new StringBuilder();
        for (Widget w = gui.child; w != null; w = w.next) {
            if (!(w instanceof Window) || !CheeseRackReader.WINDOW_CAP.equals(((Window) w).cap))
                continue;
            for (Widget c = w.child; c != null; c = c.next) {
                if (!(c instanceof NInventory))
                    continue;
                NInventory inv = (NInventory) c;
                sb.append("-- open Rack window: isz=").append(inv.isz).append(" parentGob=")
                  .append(inv.parentGob == null ? "null" : inv.parentGob.id + " " + inv.parentGob.ngob.name).append('\n');
                List<String> rows = new ArrayList<>();
                for (Widget i = inv.child; i != null; i = i.next) {
                    if (!(i instanceof WItem) || !(((WItem) i).item instanceof NGItem))
                        continue;
                    NGItem it = (NGItem) ((WItem) i).item;
                    List<NGItem.NContent> content = it.content();
                    String name = (content == null || content.isEmpty()) ? "(empty)" : content.get(0).name();
                    rows.add("   slot=" + i.c.div(Inventory.sqsz) + " item=" + it.name() + " content=" + name
                            + " tier=" + CheeseRackShelves.tierOfName(name));
                }
                for (String r : rows)
                    sb.append(r).append('\n');
            }
        }
        return sb.toString();
    }

    private static String tileName(NGameUI gui, Gob g) {
        try {
            MCache map = gui.map.glob.map;
            Resource r = map.tilesetr(map.gettile(g.rc.floor(MCache.tilesz)));
            return r == null ? "null" : r.name;
        } catch (Loading l) {
            return "(loading)";
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
        for (int i = 0; b != null && i < b.length; i++) {
            if (i > 0)
                sb.append(',');
            sb.append(b[i] & 0xFF);
        }
        return sb.append(']').toString();
    }
}
