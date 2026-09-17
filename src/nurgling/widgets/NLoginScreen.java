package nurgling.widgets;

import haven.*;
import nurgling.NConfig;
import nurgling.conf.NCharTags;
import nurgling.widgets.login.NBackdrop;
import nurgling.widgets.login.NLoginPanel;
import nurgling.widgets.login.NLoginStatusBar;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Login screen: the art as it always was, a scrim on its left edge, and on it the login form with
 * saved accounts ({@link NLoginPanel}), plus a status line along the bottom. Saved accounts are
 * token-only; nothing here writes a password anywhere.
 */
public class NLoginScreen extends LoginScreen {
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long BASE_RETRY_DELAY_MS = 1000;
    private static final long MAX_RETRY_DELAY_MS = 30000;
    private static final int VERSION_CHECK_TIMEOUT_MS = 2000;
    private static final int MARGIN = UI.scale(64);

    private IButton discordBtn;
    private NLoginStatusBar statusbar;
    /* Bot mode: set by the server's "login" prompt, cleared once an automatic attempt is sent. */
    private boolean autoPending = false;
    private int retryAttempt = 0;
    private long nextRetryTime = 0;
    /* Account of the attempt in flight; it becomes "last used" if the screen closes on success. */
    private String pending = null;
    /* Fixed top for the form: it is centred once per window size and then stays put, so the form
     * growing or shrinking (password vs saved account, connecting) never moves what is on screen. */
    private int formtop = -1;
    /* Vertical room for the form, between the top margin and the status line. */
    private int formmin = 0, formmax = 0;

    /**
     * Compares two version strings numerically.
     * @return true if remoteVersion is greater than localVersion
     */
    private static boolean isRemoteVersionNewer(String remoteVersion, String localVersion) {
        if (remoteVersion == null || localVersion == null) {
            return false;
        }
        String[] remoteParts = remoteVersion.trim().split("\\.");
        String[] localParts = localVersion.trim().split("\\.");

        int maxLength = Math.max(remoteParts.length, localParts.length);
        for (int i = 0; i < maxLength; i++) {
            int remotePart = i < remoteParts.length ? parseVersionPart(remoteParts[i]) : 0;
            int localPart = i < localParts.length ? parseVersionPart(localParts[i]) : 0;

            if (remotePart > localPart) {
                return true;
            } else if (remotePart < localPart) {
                return false;
            }
        }
        return false; // versions are equal
    }

    private static int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public NLoginScreen(String hostname) {
        super(hostname);
        discordBtn = add(new IButton("nurgling/hud/buttons/discord/", "u", "d", "h") {
            @Override
            public void click() {
                try {
                    ui.wnd.toolkit().browse(java.net.URI.create("https://discord.com/invite/3YF5yaKKPn"));
                } catch (Exception e) {
                    System.err.println("[NLoginScreen] Failed to open Discord link: " + e.getMessage());
                }
            }
        });
        statusbar = add(new NLoginStatusBar(HttpStatus.mond.get(), sz.x - (2 * MARGIN)));
        layout();
        startVersionCheck();
    }

    /* Both are called from the LoginScreen constructor, before this class's fields are set. */
    @Override
    protected Widget mkbg() {
        return (new NBackdrop(() -> bg, NBackdrop.SCRIMW));
    }

    @Override
    protected Widget mkcredbox() {
        return (new NLoginPanel(confname, this::submit));
    }

    private NLoginPanel panel() {
        return ((NLoginPanel) login);
    }

    /* LoginScreen centres the art-sized screen in the window; lay out again whenever that moves. */
    @Override
    public void presize() {
        super.presize();
        if (statusbar != null)
            layout();
    }

    /* The form sits on the scrim; Options and Discord top right; the status line along the bottom.
     * Everything is kept inside the part of the art the window actually shows, since a window
     * shorter than the art crops it top and bottom. */
    private void layout() {
        int vtop = 0, vbot = sz.y;
        if (parent != null) {
            vtop = Math.max(0, -c.y);
            vbot = Math.min(sz.y, parent.sz.y - c.y);
        }
        optbtn.move(Coord.of(sz.x - optbtn.sz.x - UI.scale(20), vtop + UI.scale(20)));
        discordBtn.move(Coord.of(optbtn.c.x - UI.scale(12) - discordBtn.sz.x, optbtn.c.y + ((optbtn.sz.y - discordBtn.sz.y) / 2)));
        statusbar.move(Coord.of(MARGIN, vbot - statusbar.sz.y - UI.scale(10)));
        formmin = vtop + UI.scale(40);
        formmax = statusbar.c.y - UI.scale(12);
        panel().budget(formmax - formmin);
        formtop = -1;
        placeform();
    }

    /* Centred on the form's tallest arrangement, then fixed: switching between password entry and a
     * saved account, or connecting, never moves what is on screen. */
    private void placeform() {
        if (formtop < 0)
            formtop = formmin + Math.max(0, ((formmax - formmin) - panel().stableh()) / 2);
        login.move(Coord.of(MARGIN, formtop));
    }

    /* The form changes height as it switches between password, saved-account and busy states. */
    @Override
    public void cresize(Widget ch) {
        if ((ch == login) && (statusbar != null))
            placeform();
    }

    @Override
    public void uimsg(String msg, Object... args) {
        if (msg == "login") {
            login.show();
            panel().ready();
            if (NConfig.isBotMod())
                autoPending = true;
        } else if (msg == "prg") {
            login.show();
            panel().busy((String) args[0]);
        } else if (msg == "error") {
            pending = null;
            panel().error((String) args[0]);
        } else {
            super.uimsg(msg, args);
        }
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if (autoPending && NConfig.isBotMod())
            attemptAutoLogin();
    }

    /**
     * Bot mode: logs in with the bot credentials, backing off exponentially between failures and
     * giving up (exiting) after {@link #MAX_RETRY_ATTEMPTS}, so a bad password can't hammer the auth
     * server into banning the account.
     */
    private void attemptAutoLogin() {
        if (retryAttempt >= MAX_RETRY_ATTEMPTS) {
            System.err.println("[NLoginScreen] Maximum retry attempts (" + MAX_RETRY_ATTEMPTS + ") reached. Auto-login disabled to prevent ban.");
            System.err.println("[NLoginScreen] Terminating game to prevent further connection attempts.");
            System.exit(1); // Exit code 1 indicates failure
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextRetryTime)
            return;
        autoPending = false;
        retryAttempt++;
        nextRetryTime = now + Math.min(BASE_RETRY_DELAY_MS * (1L << retryAttempt), MAX_RETRY_DELAY_MS);
        System.out.println("[NLoginScreen] Auto-login attempt " + retryAttempt + "/" + MAX_RETRY_ATTEMPTS);
        send(new AuthClient.NativeCred(NConfig.botmod.user, NConfig.botmod.pass), false);
    }

    /* A manual attempt from the form; in bot mode it restarts the automatic retry budget. */
    private void submit(AuthClient.Credentials creds, boolean savepw) {
        autoPending = false;
        retryAttempt = 0;
        nextRetryTime = 0;
        send(creds, savepw);
    }

    private void send(AuthClient.Credentials creds, boolean savepw) {
        pending = creds.authname();
        wdgmsg("login", creds, savepw);
    }

    /* The server only takes the login screen down once the connection is made. */
    @Override
    public void destroy() {
        if (pending != null)
            NCharTags.setUsed(pending, System.currentTimeMillis());
        super.destroy();
    }

    private void startVersionCheck() {
        Object baseurl = NConfig.get(NConfig.Key.baseurl);
        Thread checker = new HackThread(() -> {
            String local = readLocalVersion();
            String remote = null;
            if ((local != null) && (baseurl instanceof String)) {
                try {
                    URLConnection conn = new URL((String) baseurl).openConnection();
                    conn.setConnectTimeout(VERSION_CHECK_TIMEOUT_MS);
                    conn.setReadTimeout(VERSION_CHECK_TIMEOUT_MS);
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        remote = in.readLine();
                    }
                } catch (IOException ignored) {
                    /* Offline or no update server: just no badge. */
                }
            }
            statusbar.versions(local, isRemoteVersionNewer(remote, local) ? remote.trim() : null);
        }, "Version check");
        checker.setDaemon(true);
        checker.start();
    }

    private static String readLocalVersion() {
        if (!new File("ver").isFile())
            return (null);
        try (BufferedReader in = Files.newBufferedReader(Paths.get("ver"), StandardCharsets.UTF_8)) {
            String line = in.readLine();
            return ((line == null) || line.trim().isEmpty()) ? null : line.trim();
        } catch (IOException e) {
            return (null);
        }
    }
}
