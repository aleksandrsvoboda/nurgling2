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
    /**
     * How long to wait for the server to state this character's hearth secret before concluding
     * there is none. Only used when no "pwd" message arrives at all; an explicit empty one is
     * acted on at once.
     */
    private static final double AUTOGEN_GRACE = 10.0;
    private static final String PWCHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    /** A hearth secret is a credential: worth a real CSPRNG rather than Math.random()'s LCG. */
    private static final java.security.SecureRandom PWRNG = new java.security.SecureRandom();

    private Button pullBtn;
    private Label pullStatus;
    /** Secrets still to be replayed, as {their character, secret}. Filled off the UI thread. */
    private final ConcurrentLinkedQueue<String[]> pullQueue = new ConcurrentLinkedQueue<>();
    /** Sends of this pull that have not been written to the applied cache yet. */
    private final Map<String, String> pullSentSecrets = new LinkedHashMap<>();
    private volatile boolean pullLoading = false;
    /** True from the moment the database answers until the completion report is shown. */
    private volatile boolean pullRunning = false;
    private volatile int pullTotal = 0;
    private int pullSent = 0;
    private int pullAdded = 0;
    private int pullRecoloured = 0;
    /**
     * Every character the database knows about, handed over by the pull for the one-off recolour
     * pass. Not filtered by the applied cache: kin that are already there are exactly the ones
     * this pass exists for.
     */
    private volatile Set<String> pendingGreen = null;
    private double lastSend = 0;
    /** Latest status text; applied to the label from tick() so only the UI thread touches it. */
    private volatile String statusText = "";
    private String shownStatus = null;

    /** When this widget was built, i.e. roughly when the server started stating its contents. */
    private final double created = Utils.rtime();
    /** Set once the server has told us this character's secret, whatever its value. */
    private boolean sawPwd = false;
    /** Set once a non-empty secret is known, from the server or from the player. */
    private boolean haveSecret = false;
    /** Generation is attempted at most once per widget, whatever the outcome. */
    private boolean autogenDone = false;

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
        if(pullLoading || pullRunning)
        {
            System.out.println("[KinSecrets] pull ignored, one is already running");
            return;
        }
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
            System.out.println("[KinSecrets] pull aborted: no kin secret service");
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
        pullRecoloured = 0;
        status(L10n.get("kin.pull_loading"));
        System.out.println("[KinSecrets] pull starting: char=" + mine + " profile=" + profile
            + (force ? " (forced, ignoring applied cache)" : ""));
        svc.loadAsync(profile)
           .thenAccept(rows -> onPullLoaded(rows, mine, force))
           .exceptionally(e -> {
               pullLoading = false;
               status(L10n.get("kin.pull_failed"));
               System.out.println("[KinSecrets] pull FAILED: " + e.getMessage());
               return(null);
           });
    }

    /** Runs on a database thread: builds the send list, never touches a widget. */
    private void onPullLoaded(List<KinSecretDao.KinSecret> rows, String mine, boolean force)
    {
        NKinSecretCache cache = secrets();
        Set<String> known = new HashSet<>();
        int queued = 0;
        for(KinSecretDao.KinSecret ks : rows)
        {
            if((ks.charName == null) || ks.charName.isEmpty())
                continue;
            if(ks.charName.equals(mine))
                continue;
            known.add(ks.charName);
            if(!force && (cache != null) && cache.isApplied(mine, ks.charName, ks.secret))
                continue;
            pullQueue.add(new String[]{ks.charName, ks.secret});
            queued++;
        }
        System.out.println("[KinSecrets] pull read " + rows.size() + " row(s); " + known.size()
            + " other character(s): " + known + "; " + queued + " to send, "
            + (known.size() - queued) + " suppressed by the applied cache");
        pendingGreen = known;
        pullTotal = queued;
        pullRunning = true;
        pullLoading = false;
        if(queued > 0)
            status(L10n.get("kin.pull_progress", 0, queued));
    }

    /** True while incoming kin should be attributed to the pull we are running. */
    private boolean pullActive()
    {
        return(pullRunning && (lastSend > 0) && ((Utils.rtime() - lastSend) < SEND_GRACE));
    }

    /**
     * Colour kin that the database knows about but this pull will not add, because they are kin
     * already.
     * <p>
     * Hearth-secret kinship is mutual, so a character accumulates kin it never asked for: when a
     * friend pulls, both sides become kin and this side's entry is left in the default group. Those
     * are exactly the people this feature is about, so a pull recolours them too rather than only
     * the ones it added itself.
     * <p>
     * Matching is by name, which is the only handle the kin list has - the info panel carries an
     * avatar and a last-seen time, never the underlying character name. A kin whose name was
     * changed, locally or by a presentation name, therefore will not match.
     */
    private void greenexisting()
    {
        Set<String> known = pendingGreen;
        if(known == null)
            return;
        pendingGreen = null;
        Set<String> unmatched = new HashSet<>(known);
        List<String> kin = new ArrayList<>();
        for(Buddy b : this)
        {
            kin.add(b.name);
            unmatched.remove(b.name);
            if((b.group != GREEN_GROUP) && known.contains(b.name))
            {
                b.chgrp(GREEN_GROUP);
                pullRecoloured++;
            }
        }
        /* The unmatched list is the one that matters when a pull "does nothing": those characters
         * are in the database but no kin entry carries that name, which is what a presentation
         * name or a local nickname looks like from here. */
        System.out.println("[KinSecrets] recolour: " + pullRecoloured + " kin turned green; "
            + kin.size() + " kin in list " + kin + "; database names with no matching kin " + unmatched);
    }

    /** Recolour pass, paced drain of the send queue, and the completion report. */
    private void tickpull(double now)
    {
        greenexisting();
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
        /* Nothing sent means nothing can still be on its way, so a pull that only recoloured
         * reports at once instead of sitting through the grace period. */
        if(pullRunning && pullQueue.isEmpty() && (pendingGreen == null)
           && ((pullSent == 0) || ((now - lastSend) > SEND_GRACE)))
        {
            flushapplied();
            if((pullAdded == 0) && (pullRecoloured == 0))
                status(L10n.get("kin.pull_uptodate"));
            else
                status(L10n.get("kin.pull_done", pullAdded, pullRecoloured));
            pullRunning = false;
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
        /* The secret itself is never logged. */
        System.out.println("[KinSecrets] publishing " + (secret.isEmpty() ? "(cleared)" : "secret")
            + " for char=" + mine + " profile=" + profile);
        (secret.isEmpty() ? svc.deleteAsync(profile, mine) : svc.publishAsync(profile, mine, secret))
            .whenComplete((v, e) -> {
                if(e == null)
                {
                    System.out.println("[KinSecrets] publish ok for char=" + mine);
                }
                else
                {
                    System.out.println("[KinSecrets] publish FAILED for char=" + mine + ": " + e.getMessage());
                    lastPublished = null;
                    pendingPublish = secret;
                }
            });
    }

    // ---------------------------------------------------------- autogenerate

    /**
     * Give this character a hearth secret if it has none.
     * <p>
     * A character without one is invisible to every other client's pull - silently, since nothing
     * distinguishes it from a character nobody has met. Generating one closes that hole, but it
     * does change server-side state unasked, so it happens only when the player is actually
     * sharing secrets to a database, and never for a character whose secret they cleared by hand.
     * <p>
     * Two ways to conclude there is no secret: the server states an empty one, which is
     * definitive and acted on immediately, or it states nothing at all within
     * {@link #AUTOGEN_GRACE}. The latter is the fallback for a server that simply omits the
     * message when there is nothing to report.
     */
    private void tickautogen(double now)
    {
        if(autogenDone || haveSecret)
            return;
        if(!sawPwd && ((now - created) < AUTOGEN_GRACE))
            return;
        if(!(Boolean) NConfig.get(NConfig.Key.autoHearthSecret))
            return;
        /* Do not touch the character unless the secret is going somewhere useful. Checked every
         * tick rather than once, so switching the database on mid-session takes effect without
         * relogging - the same way a pending publish waits for it. */
        if(!(Boolean) NConfig.get(NConfig.Key.shareHearthSecret)
           || !(Boolean) NConfig.get(NConfig.Key.ndbenable))
            return;
        String mine = myChar();
        if((mine == null) || mine.isEmpty())
            return;
        NKinSecretCache cache = secrets();
        if((cache != null) && cache.isAutogenSuppressed(mine))
        {
            autogenDone = true;
            System.out.println("[KinSecrets] char=" + mine + " has no hearth secret, but it was"
                + " cleared deliberately; leaving it alone");
            return;
        }
        autogenDone = true;
        System.out.println("[KinSecrets] char=" + mine + " has no hearth secret; generating one");
        /* setpwd sends it to the server, fills the text box, and queues the publish. Running
         * before tickpublish means the generated secret replaces the pending delete that the
         * empty "pwd" queued, so the row is written once instead of deleted and re-added. */
        setpwd(randomsecret());
        GameUI gui = getparent(GameUI.class);
        if(gui != null)
            gui.msg(L10n.get("kin.autogen_msg"), java.awt.Color.YELLOW);
        status(L10n.get("kin.autogen_status"));
    }

    private static String randomsecret()
    {
        StringBuilder buf = new StringBuilder(8);
        for(int i = 0; i < 8; i++)
            buf.append(PWCHARS.charAt(PWRNG.nextInt(PWCHARS.length())));
        return(buf.toString());
    }

    @Override
    public void setpwd(String pass)
    {
        super.setpwd(pass);
        String mine = myChar();
        NKinSecretCache cache = secrets();
        if((pass == null) || pass.isEmpty())
        {
            /* Clear is the only unambiguous "this character should have no secret", so it both
             * stops generation now and survives to the next login. */
            autogenDone = true;
            haveSecret = false;
            if(cache != null)
                cache.suppressAutogen(mine);
        }
        else
        {
            haveSecret = true;
            if(cache != null)
                cache.allowAutogen(mine);
        }
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
        tickautogen(now);
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
            String cur = ((args.length > 0) && (args[0] instanceof String)) ? (String) args[0] : "";
            sawPwd = true;
            if(!cur.isEmpty())
                haveSecret = true;
            queuePublish(cur);
        }
        else if(msg.equals("add") && pullActive())
        {
            /* "bypwd" is not acknowledged - a successful add is the only signal there is - so any
             * kin arriving while the pull is draining is taken to be ours and painted green. */
            Buddy b = find(((Number)args[0]).intValue());
            if(b != null)
            {
                pullAdded++;
                System.out.println("[KinSecrets] pull added kin '" + b.name + "' (id " + b.id + ")");
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
