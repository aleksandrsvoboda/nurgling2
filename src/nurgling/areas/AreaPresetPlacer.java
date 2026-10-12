package nurgling.areas;

import haven.*;
import haven.render.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NMapView;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;

import static nurgling.i18n.L10n.get;

/**
 * Places an {@link AreaPreset} on the map: a ghost of every area follows the cursor (the cursor
 * holds the middle of the preset), the wheel or R turns it, F mirrors it, a left click places and a
 * right click or Esc cancels. Same idea as the tree blueprints' BlueprintPlob.
 *
 * Areas can only be made on loaded grids ({@link NArea.Space} looks every corner's grid up), so the
 * ghost turns red when any part of it is off the loaded map and a click there does nothing. Areas
 * landing on existing ones are drawn orange: allowed, but worth seeing.
 */
public class AreaPresetPlacer implements MapView.Grabber
{
    private enum State { OK, OVERLAP, BAD }

    private static final MCache.OverlayInfo overlapol = info(new Color(233, 156, 84, 110));
    private static final MCache.OverlayInfo badol = info(new Color(230, 50, 40, 110));

    private final NMapView map;
    private final AreaPreset base;
    private final Runnable onCancel;
    private AreaPreset cur;
    private int turns = 0;
    private boolean mirror = false;
    private Coord centre = null;
    private final List<MCache.OverlayInfo> colours = new ArrayList<>();
    private final List<MCache.Overlay> ols = new ArrayList<>();
    private final List<State> states = new ArrayList<>();
    /** Tile bounds of the areas already on the loaded map, for the overlap tint. */
    private final List<Area> existing = new ArrayList<>();
    private boolean done = false;

    private AreaPresetPlacer(NMapView map, AreaPreset preset, Runnable onCancel)
    {
        this.map = map;
        this.base = preset;
        this.onCancel = onCancel;
        this.cur = preset;
        for (AreaPreset.Entry e : preset.areas) {
            Color c = e.color;
            colours.add(info(new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(c.getAlpha(), 90))));
        }
        synchronized (map.glob.map.areas) {
            for (NArea a : map.glob.map.areas.values()) {
                Area b = a.getArea(map.glob.map);
                if (b != null)
                    existing.add(b);
            }
        }
    }

    /** Starts placing; {@code onCancel} runs if the player backs out. Returns false if the map is busy. */
    public static boolean start(NGameUI gui, AreaPreset preset, Runnable onCancel)
    {
        NMapView map = (NMapView) gui.map;
        if (map == null || map.presetPlacer != null || map.isAreaSelectionMode.get()) {
            gui.error(get("area.preset.busy"));
            return false;
        }
        AreaPresetPlacer placer = new AreaPresetPlacer(map, preset, onCancel);
        map.presetPlacer = placer;
        map.grab(placer);
        gui.setfocus(map);
        gui.msg(get("area.preset.place_hint", preset.name));
        if (map.ui.mc != null && map.ui.mc.isect(map.rootpos(), map.sz))
            placer.mmousemove(map.ui.mc.sub(map.rootpos()));
        return true;
    }

    private static MCache.OverlayInfo info(Color c)
    {
        Material mat = new Material(new BaseColor(c), States.maskdepth);
        return new MCache.OverlayInfo() {
            public Collection<String> tags() {
                return Collections.singletonList("show");
            }

            public Material mat() {
                return mat;
            }
        };
    }

    /** Top-left tile of the preset with the cursor at {@code centre}. */
    private Coord origin()
    {
        return centre.sub(cur.w / 2, cur.h / 2);
    }

    private Area tiles(AreaPreset.Entry e)
    {
        return Area.sized(origin().add(e.x, e.y), Coord.of(e.w, e.h));
    }

    private boolean loaded(Area a)
    {
        Coord last = a.br.sub(1, 1);
        Coord[] corners = {a.ul, last, Coord.of(a.ul.x, last.y), Coord.of(last.x, a.ul.y)};
        synchronized (map.glob.map.grids) {
            for (Coord c : corners) {
                if (!map.glob.map.grids.containsKey(c.div(MCache.cmaps)))
                    return false;
            }
        }
        return true;
    }

    private boolean overlaps(Area a)
    {
        for (Area b : existing) {
            if (a.isects(b))
                return true;
        }
        return false;
    }

    /** Redraws the ghost where it is now, rebuilding only the overlays whose tint changed. */
    private void refresh()
    {
        if (done || centre == null)
            return;
        List<Area> rects = new ArrayList<>();
        boolean allLoaded = true;
        for (AreaPreset.Entry e : cur.areas) {
            Area a = tiles(e);
            rects.add(a);
            if (!loaded(a))
                allLoaded = false;
        }
        for (int i = 0; i < rects.size(); i++) {
            Area a = rects.get(i);
            State st = !allLoaded ? State.BAD : overlaps(a) ? State.OVERLAP : State.OK;
            if (i < ols.size() && states.get(i) == st) {
                ols.get(i).update(a);
                continue;
            }
            MCache.OverlayInfo oi = (st == State.BAD) ? badol : (st == State.OVERLAP) ? overlapol : colours.get(i);
            MCache.Overlay ol = map.glob.map.new Overlay(a, oi);
            if (i < ols.size()) {
                ols.get(i).destroy();
                ols.set(i, ol);
                states.set(i, st);
            } else {
                ols.add(ol);
                states.add(st);
            }
        }
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
        List<Area> rects = new ArrayList<>();
        int overlapping = 0;
        for (AreaPreset.Entry e : cur.areas) {
            Area a = tiles(e);
            if (!loaded(a)) {
                gui.error(get("area.preset.place_unloaded"));
                return;
            }
            if (overlaps(a))
                overlapping++;
            rects.add(a);
        }
        String folder = freeFolder(gui.areas.currentPath, cur.name);
        synchronized (map.glob.map.areas) {
            for (int i = 0; i < rects.size(); i++) {
                AreaPreset.Entry e = cur.areas.get(i);
                Area a = rects.get(i);
                NArea.Space space = new NArea.Space(a.ul, a.br.sub(1, 1));
                NArea area = map.addArea(space, e.name, folder + e.path);
                area.color = e.color;
                area.hide = e.hide;
                area.jin = new org.json.JSONArray(e.in.toString());
                area.jout = new org.json.JSONArray(e.out.toString());
                area.jspec = new org.json.JSONArray(e.spec.toString());
                area.spec.clear();
                for (int s = 0; s < e.spec.length(); s++) {
                    org.json.JSONObject js = e.spec.getJSONObject(s);
                    NArea.Specialisation sp = new NArea.Specialisation(js.getString("name"), js.optString("subtype", null));
                    if (js.has(NArea.MIN_HARVEST_STAGE_JSON))
                        sp.minHarvestStage = js.getInt(NArea.MIN_HARVEST_STAGE_JSON);
                    area.spec.add(sp);
                }
                area.pileFillDirection = e.fill;
            }
        }
        NConfig.needAreasUpdate();
        finish();
        gui.msg(get("area.preset.placed", cur.name, folder));
        if (overlapping > 0)
            gui.msg(get("area.preset.overlap", overlapping));
        /* The Areas window rebuilds every name label when it opens, so drop the old ones first
         * (including the ones addArea just made) or they would be drawn twice. */
        map.destroyDummys();
        gui.areas.show();
        gui.areas.raise();
        gui.areas.showPath(folder);
    }

    /** {@code parent}/{@code name}, or "name 2", "name 3"... if that folder is already used. */
    private String freeFolder(String parent, String name)
    {
        String clean = AreaPaths.clean(name.replace('/', '-'));
        if (clean.isEmpty())
            clean = "Preset";
        Set<String> used = new HashSet<>(((NGameUI) map.ui.gui).areas.emptyFolders);
        synchronized (map.glob.map.areas) {
            for (NArea a : map.glob.map.areas.values())
                used.addAll(AreaPaths.crumbs(a.path));
        }
        String folder = AreaPaths.join(parent, clean);
        for (int i = 2; used.contains(folder); i++)
            folder = AreaPaths.join(parent, clean + " " + i);
        return folder;
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
        for (MCache.Overlay ol : ols)
            ol.destroy();
        ols.clear();
        states.clear();
        map.release(this);
        if (map.presetPlacer == this)
            map.presetPlacer = null;
    }

    /** Keys while placing; {@code code} is an AWT key code. */
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
