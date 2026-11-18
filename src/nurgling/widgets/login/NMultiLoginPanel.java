package nurgling.widgets.login;

import haven.*;
import nurgling.conf.NCharTags;
import nurgling.conf.NSavedAccounts;
import nurgling.i18n.L10n;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.sessions.multilogin.LoginGroups;
import nurgling.sessions.multilogin.LoginRun;
import nurgling.sessions.multilogin.MultiLogin;
import nurgling.widgets.NLoginScreen;
import nurgling.widgets.cookbook.HintTextEntry;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * "Log in to several", in the login form's place on the scrim: named groups as tabs, every saved
 * account with its characters to pick one of, and Run. A run logs all the picked characters in at
 * once in the background and then switches to the first; this panel shows each account's progress
 * meanwhile. Every edit is saved at once.
 */
public class NMultiLoginPanel extends Widget {
    private static final int ROWH = UI.scale(30);
    private static final int LABW = UI.scale(70);

    private final NLoginScreen screen;
    private final MultiLogin ml;
    private final LoginGroups store;
    private LoginGroups.Group group;

    private ILabel heading, problem;
    private Widget tabs;
    private Field name;
    private Scrollport accounts;
    private Button runBtn, stopBtn;
    private Progress progress;
    private boolean canRun, canStop, wasRunning;
    /* What the account list was built from; rebuilt when a character list or an online session changes. */
    private String shownFrom = "";
    private double recheck = 0;
    /* Recomputed every half second (it reads the saved logins) and right after an edit. */
    private Map<String, String> ready = new LinkedHashMap<>();

    public NMultiLoginPanel(NLoginScreen screen) {
        super(screen.panelSize());
        this.screen = screen;
        this.ml = SessionManager.getInstance().multiLogin();
        this.store = ml.groups();
        this.group = store.selected();
        build();
    }

    /* ---------------------------------------------------------------- layout */

    private void build() {
        for (Widget w = child; w != null; ) {
            Widget next = w.next;
            w.reqdestroy();
            w = next;
        }
        int w = sz.x;
        heading = add(new ILabel(L10n.get("multilogin.title"), NLoginTheme.heading), Coord.z);
        int y = heading.sz.y + UI.scale(8);
        add(new NActionLink(L10n.get("multilogin.back"), screen::closePanel), Coord.of(0, y));
        y += UI.scale(30);

        y = section(L10n.get("multilogin.group"), y);
        int linksw = UI.scale(110);
        tabs = add(groupTabs(w - linksw), Coord.of(0, y));
        NActionLink del = new NActionLink(L10n.get("multilogin.delete"), this::deleteGroup);
        NActionLink nw = new NActionLink(L10n.get("multilogin.new"), this::newGroup);
        add(del, Coord.of(w - del.sz.x, y + UI.scale(5)));
        add(nw, Coord.of(w - del.sz.x - UI.scale(12) - nw.sz.x, y + UI.scale(5)));
        y += ROWH + UI.scale(2);
        add(new ILabel(L10n.get("multilogin.name"), NLoginTheme.label), Coord.of(0, y + UI.scale(4)));
        name = add(new Field(w - LABW, L10n.get("multilogin.group_default"), this::rename), Coord.of(LABW, y));
        y += ROWH + UI.scale(4);

        /* Everything under the accounts has a fixed height; the accounts get what is left. */
        int below = UI.scale(8) + ROWH + UI.scale(6) + ROWH + UI.scale(4) + UI.scale(20) + UI.scale(110);
        y = section(L10n.get("multilogin.accounts"), y);
        NActionLink readAll = new NActionLink(L10n.get("multilogin.read_all"), this::readAll);
        add(readAll, Coord.of(w - readAll.sz.x, y - UI.scale(22)));
        add(new ILabel(L10n.get("multilogin.pick_hint"), NLoginTheme.hint), Coord.of(0, y - UI.scale(2)));
        y += UI.scale(18);
        int ah = Math.max(UI.scale(90), sz.y - y - below);
        accounts = add(new Scrollport(Coord.of(w, ah)), Coord.of(0, y));
        y += ah + UI.scale(8);

        add(new ILabel(L10n.get("multilogin.max"), NLoginTheme.label), Coord.of(0, y + UI.scale(4)));
        Field max = add(new Field(UI.scale(44), L10n.get("multilogin.max_all"), v -> store.maxAtOnce(num(v))), Coord.of(UI.scale(110), y));
        add(new ILabel(L10n.get("multilogin.max_hint"), NLoginTheme.hint), Coord.of(UI.scale(162), y + UI.scale(5)));
        y += ROWH + UI.scale(6);

        runBtn = add(new Button(UI.scale(110), L10n.get("multilogin.run"), this::run), Coord.of(0, y));
        stopBtn = add(new Button(UI.scale(110), L10n.get("multilogin.stop"), ml::stop), Coord.of(UI.scale(118), y));
        runBtn.disable(true);
        stopBtn.disable(true);
        canRun = canStop = false;
        y += ROWH + UI.scale(4);
        problem = add(new ILabel(" ", NLoginTheme.status), Coord.of(0, y));
        y += UI.scale(20);
        progress = add(new Progress(Coord.of(w, Math.max(UI.scale(40), sz.y - y))), Coord.of(0, y));

        name.settext(group.name);
        max.settext((store.maxAtOnce() > 0) ? String.valueOf(store.maxAtOnce()) : "");
        fillAccounts();
    }

    private int section(String title, int y) {
        add(new ILabel(title, NLoginTheme.section), Coord.of(0, y));
        return y + UI.scale(24);
    }

    private static int num(String v) {
        try {
            return Utils.clip(Integer.parseInt(v.trim()), 0, 100);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Lower-case account -> its character in another session ("" before one is chosen). */
    private static Map<String, String> online() {
        Map<String, String> ret = new HashMap<>();
        for (SessionContext ctx : SessionManager.getInstance().getAllSessions()) {
            if (ctx.username == null || !ctx.isConnected())
                continue;
            String chr = ctx.getDisplayName();
            ret.put(ctx.username.toLowerCase(Locale.ROOT), chr.equals(ctx.username) ? "" : chr);
        }
        return ret;
    }

    /** A fingerprint of what the account list shows, to notice when it needs building again. */
    private String listSource() {
        StringBuilder sb = new StringBuilder();
        for (NSavedAccounts.Account a : NSavedAccounts.list(screen.confname))
            sb.append(a.name).append(a.token).append(NCharTags.known(a.name)).append(';');
        sb.append(online());
        return sb.toString();
    }

    /** One block per saved account: its name, then its known characters to pick one of. */
    private void fillAccounts() {
        shownFrom = listSource();
        Scrollport.Scrollcont cont = accounts.cont;
        for (Widget c = cont.child; c != null; ) {
            Widget next = c.next;
            c.reqdestroy();
            c = next;
        }
        int w = cont.sz.x;
        int y = 0;
        List<NSavedAccounts.Account> saved = NSavedAccounts.list(screen.confname);
        if (saved.isEmpty()) {
            cont.add(new ILabel(L10n.get("multilogin.no_accounts"), NLoginTheme.hint), Coord.of(0, y));
            cont.update();
            return;
        }
        Map<String, String> on = online();
        for (NSavedAccounts.Account a : saved) {
            String account = a.name;
            cont.add(new ILabel(account, NLoginTheme.tab), Coord.of(0, y));
            String onl = on.get(account.toLowerCase(Locale.ROOT));
            if (!a.token) {
                cont.add(new ILabel(L10n.get("multilogin.no_token"), NLoginTheme.warnlabel), Coord.of(UI.scale(150), y + UI.scale(1)));
                y += UI.scale(26);
                continue;
            }
            if (onl != null) {
                String badge = onl.isEmpty() ? L10n.get("login.online") : L10n.get("login.online_as", onl);
                cont.add(new ILabel(badge, NLoginTheme.status), Coord.of(UI.scale(150), y + UI.scale(1)));
                y += UI.scale(26);
                continue;
            }
            NActionLink read = new NActionLink(L10n.get("multilogin.read"), () -> readCharacters(account));
            cont.add(read, Coord.of(w - read.sz.x - UI.scale(10), y + UI.scale(1)));
            y += UI.scale(22);
            List<String> chars = NCharTags.known(account);
            List<String> shown = new ArrayList<>(chars);
            /* A picked name the account no longer lists stays visible, so it can be changed. */
            String picked = group.picks.get(account);
            if (picked != null && !shown.contains(picked))
                shown.add(picked);
            if (shown.isEmpty()) {
                cont.add(new ILabel(L10n.get("multilogin.unknown_chars"), NLoginTheme.hint), Coord.of(UI.scale(12), y));
                y += UI.scale(22);
            }
            int x = UI.scale(12);
            for (String c : shown) {
                Widget pick = new Pick(chars.contains(c) ? c : L10n.get("multilogin.gone", c),
                        () -> c.equals(group.picks.get(account)),
                        () -> {
                            store.pick(group, account, c.equals(group.picks.get(account)) ? null : c);
                            recheck = 0;
                        });
                if (x + pick.sz.x > w - UI.scale(10)) {
                    x = UI.scale(12);
                    y += UI.scale(22);
                }
                cont.add(pick, Coord.of(x, y));
                x += pick.sz.x + UI.scale(14);
            }
            if (!shown.isEmpty())
                y += UI.scale(22);
            y += UI.scale(8);
        }
        cont.update();
    }

    private Widget groupTabs(int w) {
        List<String> names = new ArrayList<>();
        for (LoginGroups.Group g : store.list())
            names.add(g.name);
        return new Tabs(w, names, () -> group.name, this::pickGroup);
    }

    private void refreshTabs() {
        Coord c = tabs.c;
        int w = tabs.sz.x;
        tabs.reqdestroy();
        tabs = add(groupTabs(w), c);
    }

    /* ---------------------------------------------------------------- edits */

    private void pickGroup(String n) {
        LoginGroups.Group g = store.byName(n);
        if (g == null || g == group)
            return;
        group = g;
        store.select(g);
        recheck = 0;
        build();
    }

    private void newGroup() {
        group = store.add();
        recheck = 0;
        build();
    }

    private void deleteGroup() {
        if (store.list().size() <= 1)
            return;
        store.remove(group);
        group = store.selected();
        recheck = 0;
        build();
    }

    private void rename(String v) {
        if (store.rename(group, v))
            refreshTabs();
    }

    /* ---------------------------------------------------------------- runs */

    private Map<String, String> runnable() {
        return ml.runnable(group, screen.confname);
    }

    /** Why Run is off, or null. */
    private String runProblem() {
        if (ml.running())
            return L10n.get("multilogin.running");
        if (ready.isEmpty())
            return L10n.get("multilogin.nothing");
        return null;
    }

    private UILoop loop() {
        return ui.getLoop();
    }

    private void run() {
        ready = runnable();
        if (runProblem() != null || loop() == null)
            return;
        ml.start(loop(), ready);
    }

    private void readCharacters(String account) {
        if (ml.running() || loop() == null)
            return;
        List<String> one = new ArrayList<>();
        one.add(account);
        ml.readCharacters(loop(), one);
    }

    /** Reads every saved token account whose characters aren't known yet. */
    private void readAll() {
        if (ml.running() || loop() == null)
            return;
        Map<String, String> on = online();
        List<String> todo = new ArrayList<>();
        for (NSavedAccounts.Account a : NSavedAccounts.list(screen.confname)) {
            if (a.token && NCharTags.known(a.name).isEmpty() && !on.containsKey(a.name.toLowerCase(Locale.ROOT)))
                todo.add(a.name);
        }
        if (!todo.isEmpty())
            ml.readCharacters(loop(), todo);
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        boolean running = ml.running();
        if ((recheck -= dt) <= 0 || (wasRunning && !running)) {
            recheck = 0.5;
            ready = runnable();
            if (!listSource().equals(shownFrom))
                fillAccounts();
        }
        wasRunning = running;
        String why = runProblem();
        boolean run = (why == null);
        if (run != canRun) {
            canRun = run;
            runBtn.disable(!run);
        }
        if (running != canStop) {
            canStop = running;
            stopBtn.disable(!running);
        }
        String p = (why == null) ? L10n.get("multilogin.ready", ready.size()) : why;
        if (!p.equals(problem.text()))
            problem.settext(p);
    }

    @Override
    public void draw(GOut g) {
        g.chcolor(NLoginTheme.accent);
        g.frect(Coord.of(0, heading.sz.y + UI.scale(1)), Coord.of(heading.sz.x, UI.scale(2)));
        g.chcolor();
        super.draw(g);
    }

    /* ---------------------------------------------------------------- pieces */

    /** One line per account of the current or last run: where it is, or why it stopped. */
    private class Progress extends Widget {
        private final Map<String, Text> cache = new HashMap<>();
        private Text summary;
        private String summaryFrom = null;

        Progress(Coord sz) {
            super(sz);
        }

        private Text line(String s, Color col) {
            String key = s + "\u0000" + col.getRGB();
            Text t = cache.get(key);
            if (t == null) {
                if (cache.size() > 200)
                    cache.clear();
                t = NLoginTheme.body.render(s, col);
                cache.put(key, t);
            }
            return t;
        }

        @Override
        public void draw(GOut g) {
            LoginRun r = ml.run();
            if (r == null)
                return;
            int y = 0;
            String sm = r.running() ? null : r.summary();
            if (sm != null) {
                if (!sm.equals(summaryFrom)) {
                    summaryFrom = sm;
                    summary = NLoginTheme.cname.render(L10n.get("multilogin.done", sm));
                }
                g.image(summary.tex(), Coord.of(0, y));
                y += summary.sz().y + UI.scale(2);
            }
            for (LoginRun.Entry e : r.entries()) {
                if (y + UI.scale(16) > sz.y)
                    break;
                String who = (e.character == null) ? e.account : e.account + " · " + e.character;
                String what = L10n.get("multilogin.state." + e.state().name().toLowerCase(Locale.ROOT));
                if (e.why() != null)
                    what = what + ": " + e.why();
                Color col = (e.state() == LoginRun.State.FAILED) ? NLoginTheme.err
                        : (e.state() == LoginRun.State.IN_GAME || e.state() == LoginRun.State.READ) ? NLoginTheme.ok
                        : NLoginTheme.note;
                Text t = line(who + ": " + what, col);
                g.image(t.tex(), Coord.of(0, y));
                y += t.sz().y + UI.scale(1);
            }
        }
    }

    /** A character to pick: a square box filled in the accent colour when picked, then the name. */
    private static class Pick extends Widget {
        private static final int BOX = UI.scale(10);
        private final Text text;
        private final Supplier<Boolean> on;
        private final Runnable toggle;
        private boolean hover = false;

        Pick(String label, Supplier<Boolean> on, Runnable toggle) {
            super(Coord.z);
            this.text = NLoginTheme.body.render(label);
            this.on = on;
            this.toggle = toggle;
            resize(Coord.of(BOX + UI.scale(6) + text.sz().x, Math.max(BOX, text.sz().y)));
        }

        @Override
        public void draw(GOut g) {
            int by = (sz.y - BOX) / 2;
            NLoginTheme.outline(g, Coord.of(0, by), Coord.of(BOX, BOX));
            if (on.get()) {
                g.chcolor(NLoginTheme.accent);
                g.frect(Coord.of(UI.scale(2), by + UI.scale(2)), Coord.of(BOX - UI.scale(4), BOX - UI.scale(4)));
                g.chcolor();
            } else if (!hover) {
                g.chcolor(NLoginTheme.dim);
            }
            g.image(text.tex(), Coord.of(BOX + UI.scale(6), (sz.y - text.sz().y) / 2));
            g.chcolor();
        }

        @Override
        public void mousemove(MouseMoveEvent ev) {
            hover = ev.c.isect(Coord.z, sz);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if (ev.b == 1) {
                toggle.run();
                return true;
            }
            return super.mousedown(ev);
        }
    }

    /** A text field that saves on every edit. */
    private static final class Field extends HintTextEntry {
        private final Consumer<String> save;

        Field(int w, String hint, Consumer<String> save) {
            super(w, hint, null);
            this.save = save;
        }

        @Override
        protected void changed() {
            super.changed();
            /* Null while TextEntry's constructor sets the initial text. */
            if (save != null)
                save.accept(text());
        }

        @Override
        public void activate(String text) {
            /* Enter only confirms; nothing goes to the server. */
        }
    }

    /** The groups as text tabs on a hairline, the picked one underlined in the accent colour. */
    private static final class Tabs extends Widget {
        private final List<String> opts;
        private final Supplier<String> selected;
        private final Consumer<String> pick;
        private final List<Text> names = new ArrayList<>();
        private final List<Integer> xs = new ArrayList<>(), ws = new ArrayList<>();
        private int hover = -1;

        Tabs(int w, List<String> opts, Supplier<String> selected, Consumer<String> pick) {
            super(Coord.of(w, UI.scale(26)));
            this.opts = opts;
            this.selected = selected;
            this.pick = pick;
            int content = 0;
            for (String o : opts) {
                Text t = NLoginTheme.tab.render(o);
                names.add(t);
                content += t.sz().x;
            }
            int pad = Utils.clip((w - content) / (2 * Math.max(opts.size(), 1)), UI.scale(4), UI.scale(12));
            int x = 0;
            for (Text t : names) {
                xs.add(x);
                ws.add(t.sz().x + 2 * pad);
                x += t.sz().x + 2 * pad;
            }
        }

        @Override
        public void draw(GOut g) {
            g.chcolor(255, 255, 255, 36);
            g.frect(Coord.of(0, sz.y - 1), Coord.of(sz.x, 1));
            g.chcolor();
            String sel = selected.get();
            for (int i = 0; i < opts.size(); i++) {
                boolean on = opts.get(i).equals(sel);
                Text t = names.get(i);
                if (!on && (i != hover))
                    g.chcolor(NLoginTheme.dim);
                g.image(t.tex(), Coord.of(xs.get(i) + (ws.get(i) - t.sz().x) / 2, (sz.y - t.sz().y) / 2));
                g.chcolor();
                if (on) {
                    g.chcolor(NLoginTheme.accent);
                    g.frect(Coord.of(xs.get(i), sz.y - UI.scale(2)), Coord.of(ws.get(i), UI.scale(2)));
                    g.chcolor();
                }
            }
        }

        private int at(Coord c) {
            for (int i = 0; i < xs.size(); i++) {
                if (c.x >= xs.get(i) && c.x < xs.get(i) + ws.get(i) && c.y >= 0 && c.y < sz.y)
                    return i;
            }
            return -1;
        }

        @Override
        public void mousemove(MouseMoveEvent ev) {
            hover = at(ev.c);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            int i = at(ev.c);
            if (ev.b == 1 && i >= 0) {
                pick.accept(opts.get(i));
                return true;
            }
            return super.mousedown(ev);
        }
    }
}
