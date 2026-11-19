package nurgling.colony;

import nurgling.i18n.L10n;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.sessions.squad.MemberReport;
import nurgling.sessions.squad.WorldRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The colony: its settings, base storage counts, and a snapshot of who is at home and what spawning a colonist would
 * take. Owned by the {@link SessionManager}; read by the Colony panel in Commander mode. Phase 1 plans only, it
 * never acts.
 */
public final class Colony {
    private static final long SNAPSHOT_MS = 500;

    public final ColonyConfig config = new ColonyConfig();
    public final ColonyStorage storage = new ColonyStorage();
    /** Learns each character's world from character select. */
    final ColonyWorlds worlds = new ColonyWorlds();

    public Colony() {
        nurgling.plugins.UiEvents.addListener(worlds);
    }
    private volatile Snapshot last;

    /** One look at the colony. */
    public static final class Snapshot {
        /** Colonists at home: ticked accounts, in range. */
        public final List<SpawnPlan.Member> crew;
        /** Ticked colonists not at home (away, or on another piece of map). */
        public final List<ColonistReport> away;
        /** Characters whose accounts aren't ticked. */
        public final List<ColonistReport> others;
        public final List<SpawnPlan.Row> plan;
        public final long time;

        Snapshot(List<SpawnPlan.Member> crew, List<ColonistReport> away, List<ColonistReport> others,
                 List<SpawnPlan.Row> plan, long time) {
            this.crew = crew;
            this.away = away;
            this.others = others;
            this.plan = plan;
            this.time = time;
        }
    }

    private volatile SpawnRun run;

    /** The current or last spawn run, or null. */
    public SpawnRun run() {
        return run;
    }

    /**
     * Why "Spawn colonist" can't start right now (Phase 2: a carpenter and the materials at hand), as a sentence for
     * the panel; null when it can. {@code out} receives the run's plan when it can.
     */
    public String whyNot(Snapshot s, SpawnRun.Plan[] out) {
        SpawnRun r = run;
        if (r != null && r.running())
            return L10n.get("colony.why.running");
        WorldRef home = config.home();
        if (home == null)
            return L10n.get("colony.why.home");
        SpawnPlan.Member builder = null;
        boolean busyCarpenter = false;
        for (SpawnPlan.Member m : s.crew) {
            if (!m.r.knows(SpawnPlan.CARPENTRY))
                continue;
            if (m.idle) {
                builder = m;
                break;
            }
            busyCarpenter = true;
        }
        if (builder == null)
            return L10n.get(busyCarpenter ? "colony.why.busy" : "colony.why.carpenter");
        if (builder.r.world == null)
            return L10n.get("colony.why.world", builder.r.name);
        java.util.Map<ColonyItems.Material, Integer> stored = storage.counts();
        for (java.util.Map.Entry<ColonyItems.Material, Integer> e : SpawnPlan.BEACON.entrySet()) {
            int carried = builder.r.count(e.getKey());
            if (carried < e.getValue() && !storage.zones().has(e.getKey()))
                return L10n.get("colony.why.zone", L10n.get("colony.mat." + e.getKey().name().toLowerCase()),
                                ColonyZones.SUBTYPE.get(e.getKey()));
            if (carried < e.getValue() && !routable(e.getKey()))
                return L10n.get("colony.why.noroute", L10n.get("colony.mat." + e.getKey().name().toLowerCase()),
                                ColonyZones.SUBTYPE.get(e.getKey()));
            int have = carried + ((stored == null) ? 0 : stored.getOrDefault(e.getKey(), 0));
            if (have < e.getValue())
                return L10n.get("colony.why.materials", L10n.get("colony.mat." + e.getKey().name().toLowerCase()),
                                have, e.getValue(), builder.r.name);
        }
        String account = null;
        boolean online = false, noToken = false;
        java.util.Set<String> tokens = new java.util.HashSet<>();
        for (nurgling.conf.NSavedAccounts.Account a : nurgling.conf.NSavedAccounts.list(nurgling.sessions.BackgroundLogin.confname()))
            if (a.token)
                tokens.add(a.name);
        for (String a : config.createAccounts()) {
            if (SessionManager.getInstance().findByUsername(a) != null) {
                online = true;
                continue;
            }
            if (!tokens.contains(a)) {
                noToken = true;
                continue;
            }
            account = a;
            break;
        }
        if (account == null)
            return L10n.get(online ? "colony.why.online" : noToken ? "colony.why.token" : "colony.why.account");
        SessionContext bctx = SessionManager.getInstance().getSession(builder.r.sessionId);
        if (bctx == null)
            return L10n.get("colony.why.carpenter");
        out[0] = new SpawnRun.Plan(bctx, account, builder.r.world, config.nextName(), home);
        return null;
    }

    private final java.util.Map<ColonyItems.Material, long[]> routeChecked = new java.util.EnumMap<>(ColonyItems.Material.class);

    /**
     * Whether chunk navigation can route to a Construction Materials zone of m (nurgling's area lookup drops zones it
     * cannot route to, so the builder would not find it). Checked at most every few seconds.
     */
    private boolean routable(ColonyItems.Material m) {
        long now = System.currentTimeMillis();
        long[] c = routeChecked.get(m);
        if (c != null && now - c[0] < ROUTE_MS)
            return c[1] != 0;
        boolean ok = nurgling.areas.NContext.findSpecGlobal(nurgling.widgets.Specialisation.SpecName.buildMaterials.toString(),
                                                            ColonyZones.SUBTYPE.get(m)) != null;
        routeChecked.put(m, new long[] {now, ok ? 1 : 0});
        return ok;
    }

    private static final long ROUTE_MS = 5000;

    /** Starts a spawn run from the viewpoint {@code vp}; returns why not, or null when it started. */
    public String spawn(nurgling.NGameUI vp) {
        SpawnRun.Plan[] p = {null};
        String why = whyNot(build(System.currentTimeMillis()), p);
        if (why != null)
            return why;
        if (vp == null || vp.ui == null || vp.ui.loop == null)
            return L10n.get("colony.why.loop");
        SpawnRun r = new SpawnRun(this, p[0], vp.ui.loop);
        run = r;
        r.start();
        return null;
    }

    /** Moves home to {@code where}; the next snapshot uses it. */
    public void setHome(WorldRef where) {
        config.setHome(where);
        last = null;
    }

    /** Forget the snapshot, e.g. after a setting changed. */
    public void invalidate() {
        last = null;
    }

    /** The latest snapshot, rebuilt when it's older than {@link #SNAPSHOT_MS}. */
    public Snapshot snapshot() {
        Snapshot s = last;
        long now = System.currentTimeMillis();
        if (s != null && now - s.time < SNAPSHOT_MS)
            return s;
        s = build(now);
        last = s;
        return s;
    }

    private Snapshot build(long now) {
        SessionManager sm = SessionManager.getInstance();
        WorldRef home = config.home();
        int radius = config.radius();
        List<SpawnPlan.Member> crew = new ArrayList<>();
        List<ColonistReport> away = new ArrayList<>();
        List<ColonistReport> others = new ArrayList<>();
        Set<String> online = new HashSet<>();
        for (SessionContext ctx : sm.getAllSessions()) {
            if (ctx.username != null)
                online.add(ctx.username);
            ColonistReport r = ctx.colonyReport;
            if (r == null || !r.fresh())
                continue;
            if (!config.joins(r.account)) {
                others.add(r);
            } else if (home != null && ColonyGeo.near(home, r.pos, radius)) {
                MemberReport mr = ctx.squadReport;
                boolean idle = mr != null && mr.status == MemberReport.Status.IDLE;
                crew.add(new SpawnPlan.Member(r, idle, config.learns(r.key)));
            } else {
                away.add(r);
            }
        }
        if (home != null)
            storage.refresh(ColonyZones.of(nurgling.NUtils.getGameUI()));
        List<SpawnPlan.Row> plan = (home == null) ? Collections.emptyList()
            : SpawnPlan.plan(crew, storage.counts(), config.createAccounts(), online);
        if (home != null && plan.size() > 1) {
            /* Where the beacon will stand: the Beacons area, or next to home without one. */
            boolean area = nurgling.areas.NContext.findSpecGlobal(nurgling.widgets.Specialisation.SpecName.beacons.toString()) != null;
            plan.add(2, new SpawnPlan.Row(1, area ? SpawnPlan.State.OK : SpawnPlan.State.JOB,
                                          area ? "colony.plan.spot.area" : "colony.plan.spot.home"));
        }
        return new Snapshot(crew, away, others, plan, now);
    }
}
