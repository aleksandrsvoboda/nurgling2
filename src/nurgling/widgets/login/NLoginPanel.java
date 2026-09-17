package nurgling.widgets.login;

import haven.*;
import nurgling.NConfig;
import nurgling.NStyle;
import nurgling.conf.NSavedAccounts;
import nurgling.conf.NSavedAccounts.Account;
import nurgling.i18n.L10n;
import nurgling.sessions.SessionManager;
import nurgling.widgets.cookbook.HintTextEntry;

import java.awt.Color;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.util.function.BiConsumer;

/**
 * The login form, laid straight onto the backdrop scrim: saved accounts, then the user name, and a
 * password field only when the account has none saved, plus Remember me and Log in. Server progress
 * and errors show here as well. The protocol is untouched: the owner turns {@code submit} into the same "login"
 * message the stock form sends.
 */
public class NLoginPanel extends Widget {
    public static final int W = UI.scale(300);
    private static final int GAP = UI.scale(12), TIGHT = UI.scale(3);
    /** The list keeps at least this many rows even when the window is very short. */
    private static final int MINROWS = 3;

    private final String confname;
    private final BiConsumer<AuthClient.Credentials, Boolean> submit;
    private Banner err, info;
    private Section section;
    private NAccountList accounts;
    private ILabel userlbl, passlbl, caps, remhint, keyhint;
    private Field user, pass;
    private IButton eye;
    private TokenLine tokline;
    private CheckBox remember, obf;
    private Progress prog;
    private Button forget, loginbtn;
    /** The saved account whose name is in the user field, or null for a password login. */
    private Account cur = null;
    private boolean busy = false, capson = false, capsok = true;
    private double capscheck = 0;
    /** Height the screen can give the form (-1: not told yet), and our height in the tallest mode. */
    private int budget = -1, stableh = 0;

    public NLoginPanel(String confname, BiConsumer<AuthClient.Credentials, Boolean> submit) {
        super(Coord.of(W, 0));
        this.confname = confname;
        this.submit = submit;
        setfocustab(true);
        err = add(new Banner(true));
        info = add(new Banner(false));
        section = add(new Section());
        accounts = add(new NAccountList(W, new NAccountList.Listener() {
            public void select(Account a) {
                if (busy)
                    return;
                if (a == NAccountList.ANOTHER) {
                    user.settext("");
                    pass.settext("");
                    NLoginPanel.this.setfocus(user);
                } else {
                    user.settext(a.name);
                }
                userchanged();
            }

            public void activate(Account a) {
                select(a);
                enter();
            }

            public void remove(Account a) {
                NSavedAccounts.remove(confname, a.name);
                if (a.name.equals(user.text()))
                    user.settext("");
                reload();
            }

            public void reorder(java.util.List<Account> order) {
                java.util.List<String> names = new java.util.ArrayList<>();
                for (Account a : order)
                    names.add(a.name);
                NSavedAccounts.reorder(names);
            }
        }));
        userlbl = add(new ILabel(L10n.get("login.username"), NLoginTheme.label));
        user = add(new Field(L10n.get("login.user_hint"), false));
        passlbl = add(new ILabel(L10n.get("login.password"), NLoginTheme.label));
        pass = add(new Field(L10n.get("login.pass_hint"), true));
        eye = add(new IButton(NStyle.visi[0].back, NStyle.visi[1].back, NStyle.visi[2].back, () -> pass.togglepw()));
        eye.settip(L10n.get("login.show_pw_tip"));
        caps = add(new ILabel(L10n.get("login.caps"), NLoginTheme.warnlabel));
        tokline = add(new TokenLine());
        remember = add(new CheckBox(L10n.get("login.remember_me")));
        remember.a = true;
        remember.settip(L10n.get("login.remember_tip"), true);
        remhint = add(new ILabel(L10n.get("login.remember_hint"), NLoginTheme.hint));
        /* Same setting as Options > QoL: AuthClient reads it when it opens the connection, so it
         * takes effect on the next attempt - which is why it belongs here and not only in Options. */
        obf = add(new CheckBox(L10n.get("login.obfuscate")));
        obf.a = Boolean.TRUE.equals(NConfig.get(NConfig.Key.alwaysObfuscate));
        obf.settip(L10n.get("login.obfuscate_tip"), true);
        obf.changed = a -> NConfig.set(NConfig.Key.alwaysObfuscate, a);
        prog = add(new Progress());
        keyhint = add(new ILabel(L10n.get("login.keys_hint"), NLoginTheme.hint));
        forget = add(new Button(UI.scale(80), L10n.get("login.forget_me"), this::forgetcur));
        loginbtn = add(new Button(UI.scale(110), L10n.get("login.button"), this::enter));

        int running = SessionManager.getInstance().getAllSessions().size();
        if (running > 0)
            info.set(L10n.get("login.adding_session", running));
        NSavedAccounts.migrate(confname);
        accounts.set(NSavedAccounts.list(confname));
        Account first = null;
        for (Account a : NSavedAccounts.list(confname)) {
            first = a;
            break;
        }
        user.settext((first != null) ? first.name : Utils.getpref("loginname@" + confname, ""));
        userchanged();
    }

    /**
     * The height the screen can give the form. The account list takes whatever is left after
     * everything else, so accounts are not stuck behind a scrollbar while the screen has room.
     */
    public void budget(int h) {
        if (h == budget)
            return;
        budget = h;
        relayout();
    }

    /**
     * Height in the tallest arrangement - password entry with the Caps Lock line showing. The screen
     * centres on this, so switching to a saved account or back never moves anything on screen.
     */
    public int stableh() {
        return (stableh);
    }

    /* ---------------------------------------------------------------- state from the server */

    /** The server is waiting for credentials (first time, or again after a failure). */
    public void ready() {
        busy = false;
        /* Options may have changed it while this screen was up. */
        obf.a = Boolean.TRUE.equals(NConfig.get(NConfig.Key.alwaysObfuscate));
        reload();
        if (parent != null)
            parent.setfocus(this);
        if (user.text().isEmpty() || (cur != null))
            setfocus(user);
        else
            setfocus(pass);
    }

    public void busy(String what) {
        busy = true;
        prog.set(what);
        sync();
    }

    public void error(String msg) {
        busy = false;
        err.set(msg);
        sync();
    }

    /* -------------------------------------------------------------------------- actions */

    private void enter() {
        if (busy)
            return;
        String nm = user.text();
        if (nm.trim().isEmpty()) {
            setfocus(user);
            return;
        }
        if ((cur != null) && cur.token) {
            byte[] tok = Bootstrap.gettoken(cur.name, confname);
            if (tok != null) {
                go(new AuthClient.TokenCred(cur.name, tok), false);
                return;
            }
            reload();
        }
        if ((cur != null) && !cur.token && (cur.pass != null)) {
            /* Remember is forced on: the token that comes back replaces the plaintext password. */
            go(new AuthClient.NativeCred(cur.name, cur.pass), true);
            return;
        }
        String pw = pass.text();
        if (pw.isEmpty()) {
            setfocus(pass);
            return;
        }
        go(creds(nm, pw), remember.state());
    }

    private void go(AuthClient.Credentials creds, boolean savepw) {
        err.clear();
        submit.accept(creds, savepw);
    }

    /** Same rules as the stock form: a 64-character hex "password" is a pasted login token. */
    private static AuthClient.Credentials creds(String nm, String pw) {
        if (pw.length() == 64) {
            try {
                return (new AuthClient.TokenCred(nm, Utils.hex.dec(pw)));
            } catch (IllegalArgumentException e) {
                /* Not hex after all - an ordinary 64-character password. */
            }
        }
        return (new AuthClient.NativeCred(nm, pw));
    }

    private void forgetcur() {
        if (cur == null)
            return;
        NSavedAccounts.remove(confname, cur.name);
        reload();
    }

    private void reload() {
        accounts.set(NSavedAccounts.list(confname));
        userchanged();
    }

    private void userchanged() {
        cur = accounts.find(user.text());
        accounts.show(cur);
        sync();
    }

    /* --------------------------------------------------------------------------- layout */

    private void sync() {
        boolean saved = (cur != null);
        boolean haslist = (accounts.saved() > 0);
        vis(section, haslist);
        vis(accounts, haslist);
        vis(passlbl, !saved);
        vis(pass, !saved);
        vis(eye, !saved);
        vis(caps, !saved && capson);
        /* Nothing to say for a token account: the missing password field already says it. */
        boolean legacy = saved && !cur.token;
        vis(tokline, legacy);
        if (legacy)
            tokline.set(cur);
        vis(remember, !saved);
        vis(remhint, !saved);
        vis(prog, busy);
        vis(loginbtn, !busy);
        vis(forget, !busy && saved);
        vis(keyhint, !busy && haslist);
        relayout();
    }

    private static void vis(Widget w, boolean v) {
        if (w.visible != v) {
            if (v)
                w.show();
            else
                w.hide();
        }
    }

    private static int stack(Widget w, int y, int gap) {
        if (!w.visible)
            return (y);
        w.move(Coord.of(0, y));
        return (y + w.sz.y + gap);
    }

    private void relayout() {
        boolean haslist = accounts.visible;
        /* Sized against the tallest arrangement, not the current one: if the list grew and shrank as
         * the password fields came and went, rows would move under the pointer between the clicks
         * of a double-click. */
        int below = (userlbl.sz.y + TIGHT) + (user.sz.y + UI.scale(8))
            + (passlbl.sz.y + TIGHT) + (pass.sz.y + UI.scale(6)) + (caps.sz.y + UI.scale(4))
            + (remember.sz.y + TIGHT) + (remhint.sz.y + GAP) + (obf.sz.y + GAP)
            + loginbtn.sz.y + (haslist ? (UI.scale(6) + keyhint.sz.y) : 0);
        int above = (err.visible ? (err.sz.y + GAP) : 0) + (info.visible ? (info.sz.y + GAP) : 0);
        int listgap = GAP + UI.scale(4);
        if (haslist) {
            above += section.sz.y + UI.scale(4);
            int rows = NAccountList.DEFROWS;
            if (budget > 0)
                rows = Math.max(MINROWS, (budget - above - listgap - below) / NAccountList.ROWH);
            accounts.maxrows(rows);
        }
        stableh = above + (haslist ? (accounts.sz.y + listgap) : 0) + below;

        int y = 0;
        y = stack(err, y, GAP);
        y = stack(info, y, GAP);
        y = stack(section, y, UI.scale(4));
        if (haslist)
            y = stack(accounts, y, listgap);
        y = stack(userlbl, y, TIGHT);
        y = stack(user, y, UI.scale(8));
        if (pass.visible) {
            y = stack(passlbl, y, TIGHT);
            eye.move(Coord.of(W - eye.sz.x - UI.scale(4), y + ((pass.sz.y - eye.sz.y) / 2)));
            y = stack(pass, y, UI.scale(6));
            y = stack(caps, y, UI.scale(4));
        }
        y = stack(tokline, y, UI.scale(10));
        y = stack(remember, y, TIGHT);
        y = stack(remhint, y, GAP);
        y = stack(obf, y, GAP);
        /* The action row keeps its height while connecting - the spinner sits in the same slot as
         * the buttons and the hint keeps its space - so the form does not jump on submit. */
        int barh = loginbtn.sz.y;
        if (prog.visible) {
            prog.move(Coord.of(0, y + ((barh - prog.sz.y) / 2)));
        } else {
            loginbtn.move(Coord.of(W - loginbtn.sz.x, y));
            if (forget.visible)
                forget.move(Coord.of(loginbtn.c.x - UI.scale(8) - forget.sz.x, y));
        }
        y += barh;
        if (haslist) {
            y += UI.scale(6);
            keyhint.move(Coord.of(0, y));
            y += keyhint.sz.y;
        }
        resize(Coord.of(W, y));
    }

    public void tick(double dt) {
        super.tick(dt);
        if (capsok && pass.visible && ((capscheck -= dt) <= 0)) {
            capscheck = 0.3;
            boolean on;
            try {
                on = Toolkit.getDefaultToolkit().getLockingKeyState(KeyEvent.VK_CAPS_LOCK);
            } catch (UnsupportedOperationException e) {
                /* Platform can't tell (HeadlessException is one of these too): stop asking. */
                capsok = false;
                on = false;
            }
            if (on != capson) {
                capson = on;
                sync();
            }
        }
    }

    /* -------------------------------------------------------------------------- pieces */

    private class Field extends HintTextEntry {
        Field(String hint, boolean pw) {
            super(W, hint, null);
            this.pw = pw;
        }

        void togglepw() {
            pw = !pw;
            redraw();
        }

        protected void changed() {
            super.changed();
            if (this == user)
                userchanged();
        }

        public void activate(String text) {
            enter();
        }

        public boolean keydown(KeyDownEvent ev) {
            if (busy)
                return (true);
            if (ev.code == KeyEvent.VK_UP) {
                accounts.step(-1);
                return (true);
            }
            if (ev.code == KeyEvent.VK_DOWN) {
                accounts.step(1);
                return (true);
            }
            return (super.keydown(ev));
        }
    }

    /** Error (red) or information (orange) strip with wrapped text. */
    private static class Banner extends Widget {
        private final boolean error;
        private Text t;

        Banner(boolean error) {
            super(Coord.of(W, 0));
            this.error = error;
            visible = false;
        }

        void set(String s) {
            t = NLoginTheme.body.renderwrap(s, error ? new Color(242, 201, 195) : new Color(240, 220, 198), W - UI.scale(18));
            resize(Coord.of(W, t.sz().y + UI.scale(10)));
            show();
        }

        void clear() {
            if (visible)
                hide();
        }

        public void draw(GOut g) {
            Color c = error ? NLoginTheme.err : NLoginTheme.accent;
            g.chcolor(c.getRed(), c.getGreen(), c.getBlue(), 36);
            g.frect(Coord.z, sz);
            g.chcolor(c);
            g.frect(Coord.z, Coord.of(UI.scale(3), sz.y));
            g.chcolor();
            if (t != null)
                g.image(t.tex(), Coord.of(UI.scale(10), UI.scale(5)));
        }
    }

    private static class Section extends Widget {
        private final Text t, h;

        Section() {
            super(Coord.z);
            t = NLoginTheme.section.render(L10n.get("login.saved_accounts"));
            h = NLoginTheme.hint.render(L10n.get("login.saved_hint"));
            resize(Coord.of(W, Math.max(t.sz().y, h.sz().y)));
        }

        public void draw(GOut g) {
            g.image(t.tex(), Coord.of(0, sz.y - t.sz().y));
            g.image(h.tex(), Coord.of(t.sz().x + UI.scale(8), sz.y - h.sz().y - UI.scale(1)));
        }
    }

    /** Shown instead of the password field when the account is saved: just what is stored, in words. */
    private static class TokenLine extends Widget {
        private Text hint;
        private Boolean tok = null;

        TokenLine() {
            super(Coord.of(W, 0));
        }

        void set(Account a) {
            if ((tok != null) && (tok == a.token))
                return;
            tok = a.token;
            hint = NLoginTheme.meta.renderwrap(L10n.get(a.token ? "login.token_hint" : "login.legacy_hint"),
                                               a.token ? NLoginTheme.muted : NLoginTheme.warn, W);
            resize(Coord.of(W, hint.sz().y));
        }

        public void draw(GOut g) {
            if (hint != null)
                g.image(hint.tex(), Coord.z);
        }
    }

    private static class Progress extends Widget {
        private Text t = null;

        Progress() {
            super(Coord.of(W, UI.scale(28)));
            visible = false;
        }

        void set(String what) {
            t = NLoginTheme.body.render((what == null) ? "" : what);
        }

        public void draw(GOut g) {
            NLoginTheme.drawSpinner(g, Coord.of(UI.scale(10), sz.y / 2), UI.scale(7));
            if (t != null)
                g.image(t.tex(), Coord.of(UI.scale(26), (sz.y - t.sz().y) / 2));
        }
    }
}
