package nurgling.planning;

import haven.*;
import nurgling.GhostAlpha;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.PresetPlacer;
import nurgling.areas.AreaPresetPlacer;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

import static nurgling.i18n.L10n.get;

/**
 * Places a {@link PlannerPreset} on the map: ghost models of every object follow the cursor (the
 * cursor holds the middle of the preset), the wheel or R turns them, F mirrors, a left click places
 * and a right click or Esc cancels. Same controls as {@link AreaPresetPlacer}.
 *
 * The stamp moves a whole tile at a time, so each ghost keeps its sub-tile snap. Ghosts can only be
 * stored against loaded grids, so the box under the stamp turns red when any object would land off
 * the loaded map, and a click there does nothing.
 */
public class PlannerPresetPlacer implements PresetPlacer
{
    private static final MCache.OverlayInfo boxol = AreaPresetPlacer.info(new Color(233, 156, 84, 50));
    private static final MCache.OverlayInfo badol = AreaPresetPlacer.info(new Color(230, 50, 40, 110));

    private final NMapView map;
    /** The world the preview Gobs live in, so they are always removed from the same one. */
    private final Glob owner;
    private final PlannerPreset base;
    private final String parentFolderId;
    private final Runnable onCancel;
    private final Consumer<PlanningNode> onPlaced;
    private PlannerPreset cur;
    private int turns = 0;
    private boolean mirror = false;
    private Coord centre = null;
    /** One preview Gob per ghost, in {@link #ghosts} order; null while its model is still loading. */
    private final Gob[] previews;
    /** Ghosts whose model can't be loaded at all; they are placed but not previewed. */
    private final boolean[] broken;
    private MCache.Overlay box = null;
    private boolean boxBad = false;
    private boolean done = false;

    private PlannerPresetPlacer(NMapView map, PlannerPreset preset, String parentFolderId,
                                Runnable onCancel, Consumer<PlanningNode> onPlaced)
    {
        this.map = map;
        this.owner = map.glob;
        this.base = preset;
        this.cur = preset;
        this.parentFolderId = parentFolderId;
        this.onCancel = onCancel;
        this.onPlaced = onPlaced;
        int n = preset.ghostCount();
        previews = new Gob[n];
        broken = new boolean[n];
    }

    /**
     * Starts placing. A one-layer preset becomes a layer in {@code parentFolderId} (null = root).
     * {@code onCancel} runs if the player backs out, {@code onPlaced} with the new node. Returns
     * false if the map is busy.
     */
    public static boolean start(NGameUI gui, PlannerPreset preset, String parentFolderId,
                                Runnable onCancel, Consumer<PlanningNode> onPlaced)
    {
        NMapView map = (NMapView) gui.map;
        if (map == null || map.presetPlacer != null || map.isAreaSelectionMode.get()) {
            gui.error(get("planner.preset.busy"));
            return false;
        }
        PlannerPresetPlacer placer = new PlannerPresetPlacer(map, preset, parentFolderId, onCancel, onPlaced);
        map.presetPlacer = placer;
        map.grab(placer);
        gui.setfocus(map);
        gui.msg(get("area.preset.place_hint", preset.name));
        if (map.ui.mc != null && map.ui.mc.isect(map.rootpos(), map.sz))
            placer.mmousemove(map.ui.mc.sub(map.rootpos()));
        return true;
    }

    private static List<PlannerPreset.Ghost> ghosts(PlannerPreset p)
    {
        List<PlannerPreset.Ghost> res = new ArrayList<>();
        for (PlannerPreset.Layer l : p.layers)
            res.addAll(l.ghosts);
        return res;
    }

    /** Top-left tile of the preset with the cursor at {@code centre}. */
    private Coord origin()
    {
        return centre.sub(cur.w / 2, cur.h / 2);
    }

    /** Moves the preview to where it is now, making any preview Gob whose model has loaded since. */
    private void refresh()
    {
        if (done || centre == null)
            return;
        Coord2d originW = origin().mul(MCache.tilesz);
        List<PlannerPreset.Ghost> gs = ghosts(cur);
        boolean bad = false;
        for (int i = 0; i < gs.size(); i++) {
            PlannerPreset.Ghost g = gs.get(i);
            Coord2d wp = originW.add(g.x, g.y);
            if (PlanningLayerManager.gridAt(wp) == null)
                bad = true;
            if (previews[i] != null)
                previews[i].move(wp, g.angle);
            else if (!broken[i])
                previews[i] = preview(i, g, wp);
        }
        Area a = Area.sized(origin(), Coord.of(cur.w, cur.h));
        if (box != null && boxBad == bad) {
            box.update(a);
        } else {
            if (box != null)
                box.destroy();
            box = owner.map.new Overlay(a, bad ? badol : boxol);
            boxBad = bad;
        }
    }

    /** A ghost Gob drawn like the planner's own, or null while its model is loading. */
    private Gob preview(int i, PlannerPreset.Ghost g, Coord2d wp)
    {
        Gob gob = new Gob(owner, wp);
        gob.a = g.angle;
        try {
            gob.setattr(new GhostAlpha(gob));
            Message sdt = (g.sdt != null && g.sdt.length > 0) ? new MessageBuf(g.sdt) : Message.nil;
            gob.setattr(new ResDrawable(gob, Resource.remote().load(g.res), sdt));
        } catch (Loading e) {
            return null;
        } catch (Resource.BadResourceException | Resource.LoadException e) {
            broken[i] = true;
            return null;
        }
        gob.move(wp, g.angle);
        owner.oc.add(gob);
        if (gob.ngob != null)
            gob.ngob.hitBox = null;
        return gob;
    }

    private void moveTo(Coord tile)
    {
        if (!tile.equals(centre)) {
            centre = tile;
            refresh();
        }
    }

    private void retransform()
    {
        cur = base.transformed(turns, mirror);
        refresh();
    }

    private void place()
    {
        if (centre == null)
            return;
        NGameUI gui = (NGameUI) map.ui.gui;
        PlanningNode made = NUtils.getUI().core.planningLayer.placePreset(cur, origin(), parentFolderId);
        if (made == null) {
            gui.error(get("planner.preset.place_unloaded"));
            return;
        }
        finish();
        gui.msg(get("planner.preset.placed", cur.name, made.name));
        if (onPlaced != null)
            onPlaced.accept(made);
    }

    public void cancel()
    {
        if (done)
            return;
        finish();
        ((NGameUI) map.ui.gui).msg(get("area.preset.cancelled"));
        if (onCancel != null)
            onCancel.run();
    }

    private void finish()
    {
        done = true;
        for (int i = 0; i < previews.length; i++) {
            if (previews[i] != null)
                owner.oc.remove(previews[i]);
            previews[i] = null;
        }
        if (box != null)
            box.destroy();
        box = null;
        map.release(this);
        if (map.presetPlacer == this)
            map.presetPlacer = null;
    }

    @Override
    public boolean keydown(int code)
    {
        switch (code) {
            case KeyEvent.VK_R:
                turns++;
                retransform();
                return true;
            case KeyEvent.VK_F:
                mirror = !mirror;
                retransform();
                return true;
            case KeyEvent.VK_ESCAPE:
                cancel();
                return true;
        }
        return false;
    }

    @Override
    public boolean mmousedown(Coord mc, int button)
    {
        if (button == 1)
            place();
        else if (button == 3)
            cancel();
        return true;
    }

    @Override
    public boolean mmouseup(Coord mc, int button)
    {
        return true;
    }

    @Override
    public boolean mmousewheel(Coord mc, int amount)
    {
        turns += (amount > 0) ? 1 : -1;
        retransform();
        return true;
    }

    @Override
    public void mmousemove(Coord mc)
    {
        map.new Maptest(mc) {
            @Override
            protected void hit(Coord pc, Coord2d wc) {
                moveTo(wc.floor(MCache.tilesz));
            }
        }.run();
    }
}
