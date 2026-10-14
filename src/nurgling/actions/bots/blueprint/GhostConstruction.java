package nurgling.actions.bots.blueprint;

import haven.*;
import haven.res.gfx.terobjs.consobj.Consobj;
import nurgling.GhostAlpha;
import nurgling.NGameUI;
import nurgling.NGob;
import nurgling.NHitBox;
import nurgling.NISBox;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.i18n.L10n;
import nurgling.actions.PathFinder;
import nurgling.actions.RestoreResources;
import nurgling.navigation.ChunkNavManager;
import nurgling.navigation.ChunkPath;
import nurgling.pf.NHitBoxD;
import nurgling.planning.PlanningGhost;
import nurgling.planning.PlanningLayerManager;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitBuildState;
import nurgling.widgets.NBasePlannerWidget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static haven.OCache.posres;

/**
 * Builds one planner ghost: walk there, look at the spot, place the sign with the verified menu
 * button, then feed the construction window until the building stands.
 * <p>
 * Derived from {@link nurgling.actions.Build} (plob race guard, finish-sign loop, Build/drink
 * cycle) but keyed by the ghost instead of a hand-written recipe: the button comes from
 * {@link BuildPaginae}, the materials from the construction window itself. Unlike Build it
 * never waits blindly: a refused placement, a missing material or a sign that stops progressing
 * becomes a skip with a reason.
 */
final class GhostConstruction
{
    /** The run cannot continue (no water, no food). */
    static final class FatalException extends Exception
    {
        FatalException(String msg) { super(msg); }
    }

    private static final double SPOT_TOLERANCE = 1.5;
    private static final long PLOB_TIMEOUT_MS = 5000;
    private static final long PLACE_TIMEOUT_MS = 10000;
    private static final long WINDOW_TIMEOUT_MS = 5000;
    private static final long PROG_TIMEOUT_MS = 2000;
    private static final int MAX_STALLS = 3;

    private final NGameUI gui;
    private final PlanningLayerManager plan;
    private final MaterialFetcher fetcher;

    GhostConstruction(NGameUI gui, PlanningLayerManager plan, MaterialFetcher fetcher)
    {
        this.gui = gui;
        this.plan = plan;
        this.fetcher = fetcher;
    }

    void build(GhostJob job) throws InterruptedException, FatalException
    {
        PlanningGhost g = job.ghost;
        closeConstructionWindow();
        // A building already standing on a loaded ghost needs no walk.
        Coord2d pos = plan.worldPosOf(g);
        Gob there = (pos == null) ? null : realGobAt(pos);
        if (there != null && g.resName.equals(there.ngob.name))
        {
            job.status = GhostJob.Status.ALREADY_BUILT;
            return;
        }
        if (!goTo(g))
        {
            job.skip(L10n.get("bbuilder.reason.unreachable"));
            return;
        }
        pos = plan.worldPosOf(g);
        if (pos == null)
        {
            job.skip(L10n.get("bbuilder.reason.not_loaded"));
            return;
        }

        there = realGobAt(pos);
        if (there != null && g.resName.equals(there.ngob.name))
        {
            job.status = GhostJob.Status.ALREADY_BUILT;
            return;
        }
        if (there != null && isSign(there))
        {
            if (!g.resName.equals(waitSignTarget(there)))
            {
                job.skip(L10n.get("bbuilder.reason.other_sign"));
                return;
            }
        } else if (!place(job, pos))
        {
            return;
        }
        finish(job);
    }

    /* ---------------- navigation ---------------- */

    /**
     * Walk until the ghost's grid is loaded and its spot locally reachable. A spot already
     * holding a sign counts as reached: the sign is approached by its own path later.
     */
    private boolean goTo(PlanningGhost g) throws InterruptedException
    {
        Coord2d wp = plan.worldPosOf(g);
        if (wp != null && (PathFinder.isAvailable(wp) || realGobAt(wp) != null))
            return true;
        ChunkNavManager nav = (gui.map instanceof NMapView) ? ((NMapView) gui.map).getChunkNavManager() : null;
        if (nav != null && nav.isInitialized())
        {
            Coord localTile = Coord2d.of(g.ox, g.oy).floor(MCache.tilesz);
            ChunkPath path = nav.planToGridCoord(g.gridId, localTile);
            if (path != null)
                nav.navigateWithPath(path, null, gui);
        }
        return plan.worldPosOf(g) != null;
    }

    private boolean approach(Gob target) throws InterruptedException
    {
        PathFinder pf = new PathFinder(target);
        pf.isHardMode = true;
        return pf.run(gui).IsSuccess();
    }

    /* ---------------- placement ---------------- */

    private boolean place(GhostJob job, Coord2d pos) throws InterruptedException
    {
        PlanningGhost g = job.ghost;
        List<MenuGrid.Pagina> candidates = BuildPaginae.candidates(gui, g.resName);
        if (candidates.isEmpty())
        {
            job.skip(L10n.get("bbuilder.reason.no_button", g.resName));
            return false;
        }
        MapView.Plob plob = null;
        for (MenuGrid.Pagina pag : candidates)
        {
            cancelPlacing();
            pag.button().use(new MenuGrid.Interaction(1, 0));
            MapView.Plob p = waitPlob();
            if (p != null && g.resName.equals(plobRes(p)))
            {
                plob = p;
                job.buttonName = pag.button().name();
                BuildPaginae.learn(g.resName, BuildPaginae.paginaRes(pag));
                break;
            }
        }
        if (plob == null)
        {
            cancelPlacing();
            job.skip(L10n.get("bbuilder.reason.wrong_button", g.resName));
            return false;
        }
        double angle = normAngle(g.angleRadians());
        plob.a = angle;
        NHitBox hb = (plob.ngob != null && plob.ngob.hitBox != null) ? plob.ngob.hitBox : NHitBox.findCustom(g.resName);

        Gob blocker = blockerOf(hb, pos, angle);
        if (blocker != null)
        {
            cancelPlacing();
            job.skip(L10n.get("bbuilder.reason.blocked", NBasePlannerWidget.shortName(blocker.ngob.name)));
            return false;
        }

        PathFinder pf = new PathFinder(NGob.getDummy(pos, angle, hb), true);
        pf.isHardMode = true;
        if (!pf.run(gui).IsSuccess())
        {
            cancelPlacing();
            job.skip(L10n.get("bbuilder.reason.cant_walk"));
            return false;
        }

        gui.ui.dropLastError();
        gui.map.wdgmsg("place", pos.floor(posres), (int) Math.round(g.angleRadians() * 32768 / Math.PI), 1, 0);
        final String[] error = {null};
        long deadline = System.currentTimeMillis() + PLACE_TIMEOUT_MS;
        NUtils.addTask(new NTask()
        {
            @Override
            public boolean check()
            {
                String err = gui.ui.getLastError();
                if (err != null) error[0] = err;
                Gob s = realGobAt(pos);
                return error[0] != null || (s != null && isSign(s)) || System.currentTimeMillis() > deadline;
            }
        });
        Gob sign = realGobAt(pos);
        if ((sign == null || !isSign(sign)) && error[0] != null)
        {
            cancelPlacing();
            job.skip(L10n.get("bbuilder.reason.refused", error[0]));
            return false;
        }
        if (sign == null || !isSign(sign))
        {
            cancelPlacing();
            job.skip(L10n.get("bbuilder.reason.no_sign"));
            return false;
        }
        return true;
    }

    private MapView.Plob waitPlob() throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + PLOB_TIMEOUT_MS;
        for (int attempt = 0; attempt < 3; attempt++)
        {
            NUtils.addTask(new NTask()
            {
                @Override
                public boolean check()
                {
                    Loader.Future<MapView.Plob> p = gui.map.placing;
                    return (p != null && p.ready()) || System.currentTimeMillis() > deadline;
                }
            });
            // Snapshot: placing can be reassigned between the wait and the get (see Build.waitAndGetPlob).
            Loader.Future<MapView.Plob> snapshot = gui.map.placing;
            if (snapshot != null && snapshot.ready())
                return snapshot.get();
            if (System.currentTimeMillis() > deadline)
                return null;
        }
        return null;
    }

    private static String plobRes(MapView.Plob p)
    {
        ResDrawable rd = p.getattr(ResDrawable.class);
        try
        {
            return (rd != null && rd.res != null) ? rd.res.get().name : null;
        } catch (Loading l)
        {
            return null;
        }
    }

    private void cancelPlacing()
    {
        if (gui.map.placing != null)
            gui.map.uimsg("unplace");
    }

    /* ---------------- construction ---------------- */

    private void finish(GhostJob job) throws InterruptedException, FatalException
    {
        PlanningGhost g = job.ghost;
        String lastProgress = null;
        int stalls = 0;
        while (true)
        {
            // Re-resolved every pass: trips for materials or water can move the map origin.
            Coord2d pos = plan.worldPosOf(g);
            if (pos == null)
            {
                job.skip(L10n.get("bbuilder.reason.not_loaded"));
                return;
            }
            Gob sign = realGobAt(pos);
            if (sign == null)
                sign = waitAnyGobAt(pos);
            if (sign == null || !isSign(sign))
            {
                if (sign != null && g.resName.equals(sign.ngob.name))
                    job.status = GhostJob.Status.BUILT;
                else
                    job.skip(L10n.get("bbuilder.reason.sign_gone"));
                return;
            }
            Window wnd = openWindow(sign);
            if (wnd == null)
            {
                job.skip(L10n.get("bbuilder.reason.cant_open"));
                return;
            }
            Map<String, int[]> slots = readSlots(wnd);
            if (slots == null)
            {
                job.skip(L10n.get("bbuilder.reason.cant_read"));
                return;
            }
            Map<String, Integer> required = new LinkedHashMap<>();
            Map<String, Integer> missing = new LinkedHashMap<>();
            boolean carrying = false;
            for (Map.Entry<String, int[]> e : slots.entrySet())
            {
                int in = e.getValue()[0], free = e.getValue()[1];
                if (in < 0 || free < 0) continue;
                required.put(e.getKey(), in + free);
                if (free > 0)
                {
                    missing.put(e.getKey(), free);
                    if (MaterialFetcher.countInInventory(gui, e.getKey()) > 0) carrying = true;
                }
            }
            BuildRecipes.rememberFromWindow(g.resName, required);

            if (!missing.isEmpty() && !carrying)
            {
                List<String> empty = fetcher.fetch(gui, missing);
                if (empty.size() == missing.size())
                {
                    job.skip(gui.getInventory().getFreeSpace() == 0
                            ? L10n.get("bbuilder.reason.inventory_full")
                            : L10n.get("bbuilder.reason.no_material", String.join(", ", empty)));
                    return;
                }
                if (!goTo(g))
                {
                    job.skip(L10n.get("bbuilder.reason.unreachable_after"));
                    return;
                }
                continue;
            }

            StringBuilder progress = new StringBuilder();
            for (Map.Entry<String, int[]> e : slots.entrySet())
                progress.append(e.getKey()).append('=').append(e.getValue()[0]).append('/').append(e.getValue()[1]).append(';');
            progress.append(signDone(sign));
            if (progress.toString().equals(lastProgress))
            {
                if (++stalls >= MAX_STALLS)
                {
                    job.skip(L10n.get("bbuilder.reason.no_progress"));
                    return;
                }
            } else
            {
                stalls = 0;
                lastProgress = progress.toString();
            }

            NUtils.startBuild(wnd);
            long deadline = System.currentTimeMillis() + PROG_TIMEOUT_MS;
            NUtils.addTask(new NTask()
            {
                @Override
                public boolean check()
                {
                    return gui.prog != null || System.currentTimeMillis() > deadline;
                }
            });
            WaitBuildState wbs = new WaitBuildState();
            NUtils.addTask(wbs);
            if (wbs.getState() == WaitBuildState.State.TIMEFORDRINK || wbs.getState() == WaitBuildState.State.DANGER)
            {
                if (!new RestoreResources().run(gui).IsSuccess())
                    throw new FatalException(L10n.get("bbuilder.fatal.restore"));
                if (!goTo(g))
                {
                    job.skip(L10n.get("bbuilder.reason.unreachable_after"));
                    return;
                }
            }
        }
    }

    /** The sign is removed and the building added in separate updates: wait for the second. */
    private Gob waitAnyGobAt(Coord2d pos) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + WINDOW_TIMEOUT_MS;
        NUtils.addTask(new NTask()
        {
            @Override
            public boolean check()
            {
                return realGobAt(pos) != null || System.currentTimeMillis() > deadline;
            }
        });
        return realGobAt(pos);
    }

    /** {@link #signTarget}, waiting while the sign's sprite or target resource is still loading. */
    private String waitSignTarget(Gob sign) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + WINDOW_TIMEOUT_MS;
        NUtils.addTask(new NTask()
        {
            @Override
            public boolean check()
            {
                return signTarget(sign) != null || System.currentTimeMillis() > deadline;
            }
        });
        return signTarget(sign);
    }

    private void closeConstructionWindow()
    {
        Window wnd = constructionWindow();
        if (wnd != null)
            wnd.wdgmsg("close");
    }

    /** The construction window for {@code sign}, opening it when needed. */
    private Window openWindow(Gob sign) throws InterruptedException
    {
        Window wnd = constructionWindow();
        if (wnd != null)
            return wnd;
        if (!approach(sign))
            return null;
        NUtils.rclickGob(sign);
        long deadline = System.currentTimeMillis() + WINDOW_TIMEOUT_MS;
        NUtils.addTask(new NTask()
        {
            @Override
            public boolean check()
            {
                return constructionWindow() != null || System.currentTimeMillis() > deadline;
            }
        });
        return constructionWindow();
    }

    /** An open window with a "Build" button and material boxes. */
    private Window constructionWindow()
    {
        for (Widget w = gui.lchild; w != null; w = w.prev)
        {
            if (!(w instanceof Window)) continue;
            boolean build = false;
            for (Widget c = w.lchild; c != null; c = c.prev)
                if (c instanceof Button && ((Button) c).text != null && "Build".equals(((Button) c).text.text)) build = true;
            if (build && !boxes(w).isEmpty()) return (Window) w;
        }
        return null;
    }

    private static List<NISBox> boxes(Widget root)
    {
        List<NISBox> out = new ArrayList<>();
        for (Widget c = root.child; c != null; c = c.next)
        {
            if (c instanceof NISBox) out.add((NISBox) c);
            else out.addAll(boxes(c));
        }
        return out;
    }

    /** Material name to {in sign, still needed}; -1 where unreadable. Null while resources load. */
    private Map<String, int[]> readSlots(Window wnd) throws InterruptedException
    {
        final Map<String, int[]> out = new LinkedHashMap<>();
        long deadline = System.currentTimeMillis() + WINDOW_TIMEOUT_MS;
        final boolean[] ok = {false};
        NUtils.addTask(new NTask()
        {
            @Override
            public boolean check()
            {
                out.clear();
                try
                {
                    for (NISBox box : boxes(wnd))
                    {
                        Resource res = box.itemres.get();
                        Resource.Tooltip tt = res.layer(Resource.tooltip);
                        String name = (tt != null) ? tt.t : NBasePlannerWidget.shortName(res.name);
                        out.put(name, new int[]{box.calcCount(), box.calcFreeSpace()});
                    }
                    ok[0] = true;
                    return true;
                } catch (Loading l)
                {
                    return System.currentTimeMillis() > deadline;
                }
            }
        });
        return ok[0] ? out : null;
    }

    /* ---------------- spot inspection ---------------- */

    /** The real (non-ghost, non-cursor) gob standing on {@code pos}, if any. */
    private Gob realGobAt(Coord2d pos)
    {
        return realGobAt(gui, pos);
    }

    static Gob realGobAt(NGameUI gui, Coord2d pos)
    {
        Gob best = null;
        double bestDist = SPOT_TOLERANCE;
        synchronized (gui.ui.sess.glob.oc)
        {
            for (Gob gob : gui.ui.sess.glob.oc)
            {
                if (gob instanceof OCache.Virtual || gob instanceof MapView.Plob || gob.id <= 0) continue;
                if (gob.id == NUtils.playerID()) continue;
                if (gob.getattr(GhostAlpha.class) != null || gob.ngob == null || gob.ngob.name == null) continue;
                double d = gob.rc.dist(pos);
                if (d < bestDist)
                {
                    bestDist = d;
                    best = gob;
                }
            }
        }
        return best;
    }

    /** A real gob whose hitbox overlaps the building's footprint, or null. */
    private Gob blockerOf(NHitBox hb, Coord2d pos, double angle)
    {
        if (hb == null) return null;
        NHitBoxD box = new NHitBoxD(hb.begin, hb.end, pos, angle);
        Gob player = NUtils.player();
        synchronized (gui.ui.sess.glob.oc)
        {
            for (Gob gob : gui.ui.sess.glob.oc)
            {
                if (gob instanceof OCache.Virtual || gob instanceof MapView.Plob || gob.id <= 0) continue;
                if (player != null && gob.id == player.id) continue;
                if (gob.getattr(GhostAlpha.class) != null || gob.getattr(Following.class) != null) continue;
                if (gob.ngob == null || gob.ngob.hitBox == null) continue;
                if (new NHitBoxD(gob).intersects(box, false)) return gob;
            }
        }
        return null;
    }

    static boolean isSign(Gob gob)
    {
        return gob.ngob != null && "gfx/terobjs/consobj".equals(gob.ngob.name);
    }

    /** What the construction sign will become, or null while unknown. */
    static String signTarget(Gob sign)
    {
        ResDrawable rd = sign.getattr(ResDrawable.class);
        if (rd == null || !(rd.spr instanceof Consobj)) return null;
        Consobj c = (Consobj) rd.spr;
        try
        {
            return (c.built != null) ? c.built.res.get().name : null;
        } catch (Loading l)
        {
            return null;
        }
    }

    private static float signDone(Gob sign)
    {
        ResDrawable rd = sign.getattr(ResDrawable.class);
        return (rd != null && rd.spr instanceof Consobj) ? ((Consobj) rd.spr).done : -1;
    }

    /** NHitBoxD.move_ortho switches on quarter turns; a negative angle hits no case. */
    static double normAngle(double a)
    {
        a %= 2 * Math.PI;
        return (a < 0) ? a + 2 * Math.PI : a;
    }
}
