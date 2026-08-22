package nurgling.widgets;

import haven.*;
import nurgling.*;
import nurgling.conf.*;
import nurgling.db.DatabaseManager;
import nurgling.db.dao.KinSecretDao;
import nurgling.db.service.KinSecretService;
import nurgling.i18n.L10n;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class NBuddyWnd extends BuddyWnd
{
    /** Prefixed onto the last-seen text of any kin that carries a note. */
    private static final String NOTEMARK = "• ";
    private static final Text.Foundry tipf = new Text.Foundry(Text.sans, 12).aa(true);

    ICheckBox settings;
    NKinSettings ks = null;
    final Coord shift = UI.scale(16,5);

    /** Index into {@link BuddyWnd#gc} that holds Color(0, 255, 0). */
    private static final int GREEN_GROUP = 1;
    /** Seconds between two "bypwd" messages while a pull drains. */
    private static final double SEND_INTERVAL = 0.25;
    /** How long after the last "bypwd" an incoming "add" is still attributed to the pull. */
    private static final double SEND_GRACE = 10.0;
    /** Seconds between attempts to hand a pending secret to a database that is not up yet. */
    private static final double PUBLISH_RETRY = 3.0;

    private Button pullBtn;
    private Label pullStatus;
    /** Secrets still to be replayed, as {their character, secret}. Filled off the UI thread. */
    private final ConcurrentLinkedQueue<String[]> pullQueue = new ConcurrentLinkedQueue<>();
    /** Sends of this pull that have not been written to the applied cache yet. */
    private final Map<String, String> pullSentSecrets = new LinkedHashMap<>();
    private volatile boolean pullLoading = false;
    private volatile int pullTotal = 0;
    private int pullSent = 0;
    private int pullAdded = 0;
    private double lastSend = 0;
    /** Latest status text; applied to the label from tick() so only the UI thread touches it. */
    private volatile String statusText = "";
    private String shownStatus = null;

    /** Secret that still has to reach the database. Empty string means "delete my row". */
    private volatile String pendingPublish = null;
    private volatile String lastPublished = null;
    private double lastPublishTry = 0;

    private NKinSecretCache secrets = null;

    private NKinNotes notes = null;
    /** Last string actually rendered per kin, so the per-tick refresh stops re-rasterising
     *  the same text (and allocating a texture for it) every single frame. */
    private final Map<Integer, String> lastol = new HashMap<>();
    private final Map<String, Text> tipcache = new HashMap<>();

    public NBuddyWnd()
    {
        add(settings = new ICheckBox(NStyle.settingsi[0], NStyle.settingsi[1], NStyle.settingsi[2], NStyle.settingsi[3])
        {
            @Override
            public void changed(boolean val)
            {
                super.changed(val);
                if(val)
                {
                    if(ks == null)
                    {
                        ks = new NKinSettings(settings);
                        ui.root.add(ks, NUtils.getGameUI().zerg.rootpos());
                    }
                    ks.show();
                    ks.raise();
                    ks.move(NUtils.getGameUI().zerg.rootpos().sub(UI.scale(0,50)));
                }
                else
                    ks.hide();
            }
        }, new Coord(sz.x - NStyle.settingsi[0].sz().x / 2, NStyle.settingsi[0].sz().y / 2).sub(shift));

        addpullrow();
        pack();

    }

    /**
     * The vanilla window keeps its text entries private, so the pull row cannot be anchored off
     * them. It does not need to be: contentsz() is the current bottom of the children, and the
     * bottom-most one ("Add kin") carries the x the whole column is laid out on.
     */
    private void addpullrow()
    {
        Widget last = null;
        for(Widget w = child; w != null; w = w.next)
        {
            if(!w.visible || (w == settings))
                continue;
            if((last == null) || ((w.c.y + w.sz.y) > (last.c.y + last.sz.y)))
                last = w;
        }
        int bx = (last == null) ? margin1 : last.c.x;
        int by = contentsz().y + margin2;
        pullBtn = add(new Button(sz.x, L10n.get("kin.btn_pull_db"))
        {
            @Override
            public void click()
            {
                super.click();
                startPull(ui.modshift);
            }
        }, new Coord(bx, by));
        pullBtn.tooltip = Text.render(L10n.get("kin.pull_db_tip")).tex();
        pullStatus = add(new Label(" "), new Coord(bx, by + pullBtn.sz.y + UI.scale(2)));
    }

    private String genus()
    {
        GameUI gui = getparent(GameUI.class);
        if(gui instanceof NGameUI)
            return(((NGameUI) gui).getGenus());
        return(null);
    }

    private String myChar()
    {
        GameUI gui = getparent(GameUI.class);
        return((gui == null) ? null : gui.chrid);
    }

    /** Per-world record of which secrets this client has already replayed. */
    private NKinSecretCache secrets()
    {
        if(secrets == null)
        {
            String genus = genus();
            if(genus == null)
                return(null);
            secrets = NKinSecretCache.get(genus);
        }
        return(secrets);
    }

    private void status(String text)
    {
        statusText = (text == null) ? "" : text;
    }

    private static KinSecretService kinsvc()
    {
        DatabaseManager dbm = NCore.databaseManager;
        if((dbm == null) || !dbm.isReady())
            return(null);
        return(dbm.getKinSecretService());
    }

    // ---------------------------------------------------------------- pull

    /**
     * Read every secret published for this world and queue the ones this character has not
     * replayed yet. A shift-click ignores the applied cache and re-sends the whole list.
     */
    private void startPull(boolean force)
    {
        /* pullTotal stays set through the grace period after the last send, so this also stops a
         * second click from wiping the tally before the report is shown. */
        if(pullLoading || (pullTotal > 0) || !pullQueue.isEmpty())
            return;
        if(!(Boolean) NConfig.get(NConfig.Key.ndbenable))
        {
            status(L10n.get("kin.pull_db_off"));
            return;
        }
        String profile = genus();
        String mine = myChar();
        if((profile == null) || profile.isEmpty() || (mine == null) || mine.isEmpty())
        {
            status(L10n.get("kin.pull_no_world"));
            return;
        }
        KinSecretService svc = kinsvc();
        if(svc == null)
        {
            /* A connected database with no service means its table could not be created - a
             * missing DDL grant, usually - which is a different problem from the database being
             * switched off, and needs a different fix. */
            DatabaseManager dbm = NCore.databaseManager;
            status(((dbm != null) && dbm.isReady()) ? L10n.get("kin.pull_unavailable")
                                                    : L10n.get("kin.pull_db_off"));
            return;
        }
        pullLoading = true;
        pullTotal = 0;
        pullSent = 0;
        pullAdded = 0;
        status(L10n.get("kin.pull_loading"));
        svc.loadAsync(profile)
           .thenAccept(rows -> onPullLoaded(rows, mine, force))
           .exceptionally(e -> {
               pullLoading = false;
               status(L10n.get("kin.pull_failed"));
               System.out.println("[NBuddyWnd] kin secret pull failed: " + e.getMessage());
               return(null);
           });
    }

    /** Runs on a database thread: builds the send list, never touches a widget. */
    private void onPullLoaded(List<KinSecretDao.KinSecret> rows, String mine, boolean force)
    {
        NKinSecretCache cache = secrets();
        int queued = 0;
        for(KinSecretDao.KinSecret ks : rows)
        {
            if((ks.charName == null) || ks.charName.isEmpty())
                continue;
            if(ks.charName.equals(mine))
                continue;
            if(!force && (cache != null) && cache.isApplied(mine, ks.charName, ks.secret))
                continue;
            pullQueue.add(new String[]{ks.charName, ks.secret});
            queued++;
        }
        pullTotal = queued;
        pullLoading = false;
        if(queued == 0)
            status(L10n.get("kin.pull_uptodate"));
        else
            status(L10n.get("kin.pull_progress", 0, queued));
    }

    /** True while incoming kin should be attributed to the pull we are running. */
    private boolean pullActive()
    {
        return((pullTotal > 0) && (lastSend > 0) && ((Utils.rtime() - lastSend) < SEND_GRACE));
    }

    /** Paced drain of the send queue, plus the completion report. */
    private void tickpull(double now)
    {
        if(!pullQueue.isEmpty() && ((now - lastSend) >= SEND_INTERVAL))
        {
            String[] entry = pullQueue.poll();
            if(entry != null)
            {
                lastSend = now;
                pullSent++;
                pullSentSecrets.put(entry[0], entry[1]);
                wdgmsg("bypwd", entry[1]);
                status(L10n.get("kin.pull_progress", pullSent, pullTotal));
            }
        }
        if((pullTotal > 0) && pullQueue.isEmpty() && ((now - lastSend) > SEND_GRACE))
        {
            flushapplied();
            status(L10n.get("kin.pull_done", pullAdded));
            pullTotal = 0;
            pullSent = 0;
        }
    }

    /** Write the whole pull to the applied cache in one go rather than once per send. */
    private void flushapplied()
    {
        if(pullSentSecrets.isEmpty())
            return;
        NKinSecretCache cache = secrets();
        if(cache != null)
            cache.markAllApplied(myChar(), pullSentSecrets);
        pullSentSecrets.clear();
    }

    // ------------------------------------------------------------- publish

    /** Note a secret that has to reach the database; the actual write happens from tick(). */
    private void queuePublish(String secret)
    {
        if(secret == null)
            return;
        if(secret.equals(lastPublished))
            return;
        pendingPublish = secret;
    }

    /**
     * Hand the pending secret to the database if one is up. Anything missing - the manager not
     * constructed yet on a cold start, a connection still coming up - just leaves the value
     * pending for the next attempt, because losing a publish means friends silently cannot add
     * this character.
     */
    private void tickpublish(double now)
    {
        String secret = pendingPublish;
        if(secret == null)
            return;
        if((now - lastPublishTry) < PUBLISH_RETRY)
            return;
        lastPublishTry = now;
        if(!(Boolean) NConfig.get(NConfig.Key.shareHearthSecret))
            return;
        if(!(Boolean) NConfig.get(NConfig.Key.ndbenable))
            return;
        String profile = genus();
        String mine = myChar();
        if((profile == null) || profile.isEmpty() || (mine == null) || mine.isEmpty())
            return;
        KinSecretService svc = kinsvc();
        if(svc == null)
            return;
        pendingPublish = null;
        lastPublished = secret;
        (secret.isEmpty() ? svc.deleteAsync(profile, mine) : svc.publishAsync(profile, mine, secret))
            .exceptionally(e -> {
                System.out.println("[NBuddyWnd] failed to publish hearth secret: " + e.getMessage());
                lastPublished = null;
                pendingPublish = secret;
                return(null);
            });
    }

    @Override
    public void setpwd(String pass)
    {
        super.setpwd(pass);
        queuePublish(pass);
    }

    /** Per-world note store for the session this window belongs to. */
    public NKinNotes notes()
    {
        if(notes == null)
        {
            String genus = genus();
            if(genus == null)
                return(null);
            notes = NKinNotes.get(genus);
        }
        return(notes);
    }

    @Override
    protected BuddyInfo makeinfo(Coord sz, Buddy buddy)
    {
        return(new NBuddyInfo(sz, buddy));
    }

    /**
     * The vanilla info panel plus a note box pinned to its bottom. The box is placed below
     * whatever the panel's own widgets occupy - which changes with the number of kinship
     * options - rather than at a guessed offset.
     */
    public class NBuddyInfo extends BuddyInfo
    {
        private final Label lbl;
        private final NTextArea note;
        private double dirty = 0;
        /** Until the store has actually been read, an empty box means "unknown", not "blank" -
         *  flushing it would wipe a note that simply had not loaded yet. */
        private boolean loaded = false;

        public NBuddyInfo(Coord sz, Buddy buddy)
        {
            super(sz, buddy);
            lbl = add(new Label(L10n.get("kin.note")), Coord.of(margin2, 0));
            note = add(new NTextArea(Coord.of(sz.x - margin3, UI.scale(20)), ""), Coord.of(margin2, 0));
            note.onchange = () -> {if(loaded) dirty = Utils.rtime();};
            note.oncommit = this::flush;
            ckload();
            layoutnote();
        }

        private void ckload()
        {
            if(loaded)
                return;
            NKinNotes ns = notes();
            if(ns == null)
                return;
            note.settext(ns.get(buddy.id, buddy.name));
            loaded = true;
        }

        private void layoutnote()
        {
            int bot = 0;
            for(Widget w = child; w != null; w = w.next)
            {
                if((w == note) || (w == lbl))
                    continue;
                bot = Math.max(bot, w.c.y + w.sz.y);
            }
            int top = bot + margin2;
            int h = sz.y - top - lbl.sz.y - UI.scale(2) - margin2;
            if(h < UI.scale(28))
            {
                lbl.hide();
                note.hide();
                return;
            }
            lbl.show();
            note.show();
            lbl.c = Coord.of(margin2, top);
            note.c = Coord.of(margin2, top + lbl.sz.y + UI.scale(2));
            if(note.sz.y != h)
                note.resize(Coord.of(sz.x - margin3, h));
        }

        public void flush()
        {
            dirty = 0;
            if(!loaded)
                return;
            NKinNotes ns = notes();
            if(ns != null)
                ns.set(buddy.id, buddy.name, note.text());
        }

        @Override
        public void tick(double dt)
        {
            super.tick(dt);
            ckload();
            layoutnote();
            if((dirty > 0) && (Utils.rtime() - dirty > 0.4))
                flush();
        }

        @Override
        public void destroy()
        {
            if(dirty > 0)
                flush();
            super.destroy();
        }
    }

    private Text tip(String note, String name)
    {
        String text = name + "\n\n" + note;
        Text ret = tipcache.get(text);
        if(ret == null)
        {
            if(tipcache.size() > 64)
            {
                for(Text t : tipcache.values())
                    t.dispose();
                tipcache.clear();
            }
            tipcache.put(text, ret = tipf.renderwrap(text, UI.scale(240)));
        }
        return(ret);
    }

    /**
     * Kin rows are drawn by an anonymous widget in haven that never calls super.draw(), so a
     * note marker cannot be a child widget. The last-seen text is regenerated here every tick
     * anyway, so the marker rides along with it, and the note itself goes on the row tooltip.
     */
    private void marknotes()
    {
        NKinNotes ns = notes();
        if(ns == null)
            return;
        for(Widget w = child; w != null; w = w.next)
        {
            if(!(w instanceof SSearchBox))
                continue;
            for(Widget r = w.child; r != null; r = r.next)
            {
                if(!(r instanceof SListWidget.ItemWidget))
                    continue;
                Object item = ((SListWidget.ItemWidget<?>) r).item;
                if(!(item instanceof Buddy))
                    continue;
                Buddy b = (Buddy) item;
                String note = ns.get(b.id, b.name);
                r.tooltip = note.isEmpty() ? null : tip(note, b.name);
            }
        }
    }

    final Set<Integer> req = new HashSet<>();
    @Override
    public void tick(double dt)
    {
        super.tick(dt);
        double now = Utils.rtime();
        /* Publishing and the pull drain run whether or not the window is on screen: the player
         * may well close it right after pressing the button. */
        tickpublish(now);
        tickpull(now);
        if(!statusText.equals(shownStatus))
            pullStatus.settext(shownStatus = statusText);
        if(NUtils.getGameUI()!=null && NUtils.getGameUI().zerg!=null && NUtils.getGameUI().zerg.visible && parent.visible)
        {
            synchronized (req)
            {
                int count = 0;
                if (req.isEmpty())
                    for (Buddy b : buddies)
                    {
                        if ((now - b.upTime > 10 || b.upTime == 0) && count++<7)
                        {
                            wdgmsg("ch", b.id);
                            req.add(b.id);
                            b.upTime = now;
                        }
                    }
            }
            NKinNotes ns = notes();
            for (Buddy b : buddies)
            {
                String text = lastOnline(b.atime, b, null);
                if((ns != null) && ns.has(b.id, b.name))
                    text = NOTEMARK + text;
                if(!text.equals(lastol.get(b.id)) || b.lastOnline == null)
                {
                    lastol.put(b.id, text);
                    if(b.lastOnline != null)
                        b.lastOnline.dispose();
                    b.lastOnline = Text.render(text);
                }
            }
            marknotes();
        }
    }

    int lastSet = -1;

    @Override
    public void uimsg(String msg, Object... args)
    {
        synchronized (req)
        {
            if(!req.isEmpty() )
            {
                if (msg.equals("i-set"))
                {
                    if (req.contains((int) args[0]))
                    {
                        lastSet = (int) args[0];
                        req.remove((int) args[0]);
                        return;
                    }
                }
            }
            if(lastSet!=-1)
            {
                if(msg.equals("i-atime"))
                {
                    for(Buddy b : buddies)
                    {
                        if(b.id == lastSet)
                        {
                            b.atime = (long)Utils.ntime() - ((Number)args[0]).longValue();
                            lastSet = -1;
                            return;
                        }
                    }
                }
                if(msg.equals("i-ava"))
                {
                    return;
                }
            }
        }
        super.uimsg(msg, args);
        if(msg.equals("pwd"))
        {
            /* The server states this character's current secret when the widget is created, which
             * covers characters whose secret was set long before this feature existed. */
            queuePublish(((args.length > 0) && (args[0] instanceof String)) ? (String) args[0] : "");
        }
        else if(msg.equals("add") && pullActive())
        {
            /* "bypwd" is not acknowledged - a successful add is the only signal there is - so any
             * kin arriving while the pull is draining is taken to be ours and painted green. */
            Buddy b = find(((Number)args[0]).intValue());
            if(b != null)
            {
                pullAdded++;
                if(b.group != GREEN_GROUP)
                    b.chgrp(GREEN_GROUP);
            }
        }
    }

    @Override
    public void destroy()
    {
        flushapplied();
        super.destroy();
    }

}
