package nurgling.widgets.options;

import haven.*;
import haven.Button;
import haven.Label;
import nurgling.NConfig;
import nurgling.NUtils;
import nurgling.db.ConnectionDoctor;
import nurgling.db.DatabaseManager;
import nurgling.db.DbCredentials;
import nurgling.db.DbSettings;
import nurgling.db.InviteCode;
import nurgling.widgets.db.HostWizard;
import nurgling.widgets.db.VillagersWindow;
import nurgling.i18n.L10n;
import nurgling.widgets.nsettings.Panel;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedList;

/**
 * Database settings.
 *
 * <p>Laid out around one question - where does this client keep its data - instead of the old
 * checkbox-plus-dropdown pair, whose two halves could disagree. The sharing options sit below the
 * connection and are only reachable once a village database is selected, because none of them mean
 * anything until then, and a status line is always on screen: connection failures previously reached
 * the user as nothing at all.
 */
public class DatabaseSettings extends Panel {
    private static final int MODE_OFF = 0;
    private static final int MODE_SQLITE = 1;
    private static final int MODE_VILLAGE = 2;

    private final int margin = UI.scale(10);
    private final int labelX = UI.scale(10);
    private final int entryX = UI.scale(110);
    private final int entryW = UI.scale(190);

    private RadioGroup modes;
    private Label statusLabel;
    private Label statusDetail;
    private Button testButton;
    private Button villagersButton;
    private Button hostButton;

    private Label inviteLabel;
    private TextEntry inviteEntry;
    private Button inviteApply;
    private CheckBox advancedToggle;

    /* Every widget below the connection block, with the position it sits at when the block is at
     * its tallest. Collapsing the block moves them up rather than leaving a hole. */
    private final java.util.List<Widget> belowBlock = new java.util.ArrayList<>();
    private final java.util.List<Coord> belowBase = new java.util.ArrayList<>();
    private int advancedHeight, sqliteHeight;

    private Label hostLabel, portLabel, dbNameLabel, userLabel, passLabel, sslLabel;
    private TextEntry hostEntry, portEntry, dbNameEntry, usernameEntry, passwordEntry;
    private Dropbox<String> sslBox;

    private Label fileLabel;
    private TextEntry filePathEntry;
    private Button initDbButton;

    private Label sharingLabel;
    private CheckBox shareHsCheckbox, shareMapMarksCheckbox, sharePosCheckbox, showPeerPosCheckbox;
    private Button seedFishButton;

    private boolean built = false;

    private int mode = MODE_OFF;
    private boolean shareHs, shareMapMarks, sharePos, showPeerPos;
    private String sslMode = DbSettings.SSL_PREFER;
    private boolean showAdvanced = false;
    private String villageName = "";

    /* Written by the connection probe, which must not run on the UI thread, and applied in tick().
     * A probe that finishes after the panel is closed simply never gets read. */
    private volatile String probeText = null;
    private volatile Color probeColor = Color.WHITE;
    private volatile boolean probeRunning = false;

    private static final String[] SSL_VALUES = {
        DbSettings.SSL_DISABLE, DbSettings.SSL_PREFER, DbSettings.SSL_REQUIRE
    };

    public DatabaseSettings() {
        super("");
        int y = margin;

        add(new Label(L10n.get("database.storage")), new Coord(labelX, y));
        y += UI.scale(20);

        modes = new RadioGroup(this) {
            @Override
            public void changed(int btn, String lbl) {
                mode = btn;
                updateWidgetsVisibility();
            }
        };
        modes.add(L10n.get("database.mode_off"), new Coord(UI.scale(20), y));
        y += UI.scale(20);
        modes.add(L10n.get("database.mode_sqlite"), new Coord(UI.scale(20), y));
        y += UI.scale(20);
        modes.add(L10n.get("database.mode_village"), new Coord(UI.scale(20), y));
        y += UI.scale(26);

        statusLabel = add(new Label(""), new Coord(labelX, y));
        y += UI.scale(18);
        statusDetail = add(new Label(""), new Coord(labelX, y));
        y += UI.scale(20);

        testButton = add(new Button(UI.scale(150), L10n.get("database.test")) {
            @Override
            public void click() {
                super.click();
                runProbe();
            }
        }, new Coord(labelX, y));
        villagersButton = add(new Button(UI.scale(110), L10n.get("database.villagers")) {
            @Override
            public void click() {
                super.click();
                openWindow(VillagersWindow.class, VillagersWindow::new);
            }
        }, new Coord(labelX + UI.scale(160), y));
        hostButton = add(new Button(UI.scale(170), L10n.get("database.host_wizard")) {
            @Override
            public void click() {
                super.click();
                openWindow(HostWizard.class, HostWizard::new);
            }
        }, new Coord(labelX + UI.scale(280), y));
        y += testButton.sz.y + UI.scale(10);

        /* The primary way in. Everything the Advanced block asks for is in here, including the two
         * values that were never on screen before it existed: the port and the database name. */
        inviteLabel = add(new Label(L10n.get("database.invite")), new Coord(labelX, y + UI.scale(3)));
        inviteEntry = add(new TextEntry(UI.scale(330), ""), new Coord(UI.scale(90), y));
        inviteApply = add(new Button(UI.scale(90), L10n.get("database.invite_apply")) {
            @Override
            public void click() {
                super.click();
                applyInvite();
            }
        }, new Coord(UI.scale(430), y));
        inviteApply.tooltip = Text.render(L10n.get("database.invite_tip")).tex();
        y += inviteEntry.sz.y + UI.scale(8);

        advancedToggle = add(new CheckBox(L10n.get("database.advanced")) {
            public void set(boolean val) {
                a = val;
                showAdvanced = val;
                updateWidgetsVisibility();
            }
        }, new Coord(labelX, y));
        y += advancedToggle.sz.y + UI.scale(6);

        // Both connection blocks start here; only one is ever visible.
        final int blockY = y;

        int py = blockY;
        hostLabel = add(new Label(L10n.get("database.host")), new Coord(labelX, py));
        hostEntry = add(new TextEntry(entryW, ""), new Coord(entryX, py));
        py += hostEntry.sz.y + UI.scale(5);

        portLabel = add(new Label(L10n.get("database.port")), new Coord(labelX, py));
        portEntry = add(new TextEntry(UI.scale(70), ""), new Coord(entryX, py));
        py += portEntry.sz.y + UI.scale(5);

        dbNameLabel = add(new Label(L10n.get("database.dbname")), new Coord(labelX, py));
        dbNameEntry = add(new TextEntry(entryW, ""), new Coord(entryX, py));
        py += dbNameEntry.sz.y + UI.scale(5);

        userLabel = add(new Label(L10n.get("database.username")), new Coord(labelX, py));
        usernameEntry = add(new TextEntry(entryW, ""), new Coord(entryX, py));
        py += usernameEntry.sz.y + UI.scale(5);

        passLabel = add(new Label(L10n.get("database.password")), new Coord(labelX, py));
        passwordEntry = add(new TextEntry(entryW, ""), new Coord(entryX, py));
        passwordEntry.pw = true;
        py += passwordEntry.sz.y + UI.scale(5);

        sslLabel = add(new Label(L10n.get("database.encryption")), new Coord(labelX, py));
        sslBox = add(new Dropbox<String>(UI.scale(150), 3, UI.scale(16)) {
            @Override
            protected String listitem(int i) {
                return sslLabelFor(SSL_VALUES[i]);
            }

            @Override
            protected int listitems() {
                return SSL_VALUES.length;
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }

            @Override
            public void change(String item) {
                super.change(item);
                for (String v : SSL_VALUES) {
                    if (sslLabelFor(v).equals(item)) {
                        sslMode = v;
                        break;
                    }
                }
            }
        }, new Coord(entryX, py));
        py += sslBox.sz.y + UI.scale(10);

        int sy = blockY;
        fileLabel = add(new Label(L10n.get("database.sqlite_file")), new Coord(labelX, sy));
        filePathEntry = add(new TextEntry(entryW, ""), new Coord(entryX, sy));
        sy += filePathEntry.sz.y + UI.scale(5);
        initDbButton = add(new Button(UI.scale(200), L10n.get("database.init_new")) {
            @Override
            public void click() {
                super.click();
                createSqliteDatabase();
            }
        }, new Coord(labelX, sy));
        sy += initDbButton.sz.y + UI.scale(10);

        advancedHeight = py - blockY;
        sqliteHeight = sy - blockY;
        y = Math.max(py, sy) + UI.scale(6);

        sharingLabel = add(new Label(L10n.get("database.sharing")), new Coord(labelX, y));
        y += UI.scale(20);

        shareHsCheckbox = add(new CheckBox(L10n.get("database.share_hearth_secret")) {
            public void set(boolean val) {
                a = val;
                shareHs = val;
            }
        }, new Coord(UI.scale(20), y));
        shareHsCheckbox.tooltip = Text.render(L10n.get("database.share_hearth_secret_tip")).tex();
        y += shareHsCheckbox.sz.y + UI.scale(5);

        shareMapMarksCheckbox = add(new CheckBox(L10n.get("database.share_map_markers")) {
            public void set(boolean val) {
                a = val;
                shareMapMarks = val;
            }
        }, new Coord(UI.scale(20), y));
        shareMapMarksCheckbox.tooltip = Text.render(L10n.get("database.share_map_markers_tip")).tex();
        y += shareMapMarksCheckbox.sz.y + UI.scale(5);

        sharePosCheckbox = add(new CheckBox(L10n.get("database.share_position")) {
            public void set(boolean val) {
                a = val;
                sharePos = val;
            }
        }, new Coord(UI.scale(20), y));
        sharePosCheckbox.tooltip = Text.render(L10n.get("database.share_position_tip")).tex();
        y += sharePosCheckbox.sz.y + UI.scale(5);

        showPeerPosCheckbox = add(new CheckBox(L10n.get("database.show_peer_positions")) {
            public void set(boolean val) {
                a = val;
                showPeerPos = val;
            }
        }, new Coord(UI.scale(20), y));
        showPeerPosCheckbox.tooltip = Text.render(L10n.get("database.show_peer_positions_tip")).tex();
        y += showPeerPosCheckbox.sz.y + UI.scale(10);

        /* The one bridge between the JSON file and the database. Fish locations are file OR database -
         * nothing crosses automatically - so this is how spots saved before the database existed get
         * carried over. Idempotent, because a row id is derived from the spot's position and fish. */
        seedFishButton = add(new Button(UI.scale(220), L10n.get("database.seed_fish")) {
            @Override
            public void click() {
                super.click();
                seedFishLocations();
            }
        }, new Coord(labelX, y));
        seedFishButton.tooltip = Text.render(L10n.get("database.seed_fish_tip")).tex();
        y += seedFishButton.sz.y + margin;

        for (Widget w : new Widget[]{sharingLabel, shareHsCheckbox, shareMapMarksCheckbox,
                                     sharePosCheckbox, showPeerPosCheckbox, seedFishButton}) {
            belowBlock.add(w);
            belowBase.add(w.c);
        }

        sz = new Coord(UI.scale(580), Math.max(UI.scale(400), y));

        built = true;
        load();
    }

    private static String sslLabelFor(String value) {
        if (DbSettings.SSL_DISABLE.equals(value))
            return L10n.get("database.ssl_disable");
        if (DbSettings.SSL_REQUIRE.equals(value))
            return L10n.get("database.ssl_require");
        return L10n.get("database.ssl_prefer");
    }

    @Override
    public void load() {
        // A previous test result describes settings that may no longer be the ones on screen.
        probeText = null;
        boolean enabled = getBool(NConfig.Key.ndbenable);
        boolean isPostgres = getBool(NConfig.Key.postgres);
        mode = !enabled ? MODE_OFF : (isPostgres ? MODE_VILLAGE : MODE_SQLITE);
        modes.check(mode);

        shareHs = getBool(NConfig.Key.shareHearthSecret);
        shareHsCheckbox.a = shareHs;
        shareMapMarks = getBool(NConfig.Key.mapShareMarkers);
        shareMapMarksCheckbox.a = shareMapMarks;
        sharePos = getBool(NConfig.Key.sharePosition);
        sharePosCheckbox.a = sharePos;
        showPeerPos = getBool(NConfig.Key.showPeerPositions);
        showPeerPosCheckbox.a = showPeerPos;

        /* Read through DbSettings rather than off the keys, so a config that still only has the old
         * combined serverNode shows the host and port it is actually reaching - and saving then
         * writes them out properly. */
        DbSettings s = DbSettings.fromConfig();
        hostEntry.settext(s.host);
        portEntry.settext(String.valueOf(s.port));
        dbNameEntry.settext(s.database);
        usernameEntry.settext(s.user);
        passwordEntry.settext(s.password);
        sslMode = s.sslmode;
        sslBox.change(sslLabelFor(sslMode));

        filePathEntry.settext(asString(NConfig.get(NConfig.Key.dbFilePath)));
        villageName = asString(NConfig.get(NConfig.Key.dbVillage));

        /* Open the details for somebody who has nothing set up yet, closed for somebody who does -
         * the second group has no reason to look at a port again. */
        showAdvanced = s.host.isEmpty();

        updateWidgetsVisibility();
    }

    @Override
    public void save() {
        probeText = null;
        boolean wasEnabled = getBool(NConfig.Key.ndbenable);
        boolean enabled = (mode != MODE_OFF);

        NConfig.set(NConfig.Key.ndbenable, enabled);
        NConfig.set(NConfig.Key.shareHearthSecret, shareHs);
        NConfig.set(NConfig.Key.mapShareMarkers, shareMapMarks);

        /* Turning sharing off has to take the row out of the database, not merely stop refreshing
         * it: otherwise this character keeps showing on everyone's map until it ages out, which is
         * exactly the surprise an opt-out is there to prevent. */
        boolean wasSharing = getBool(NConfig.Key.sharePosition);
        NConfig.set(NConfig.Key.sharePosition, sharePos);
        NConfig.set(NConfig.Key.showPeerPositions, showPeerPos);
        if (wasSharing && !sharePos && nurgling.NCore.databaseManager != null
            && nurgling.NCore.databaseManager.getPeerPositionService() != null) {
            nurgling.NCore.databaseManager.getPeerPositionService().withdrawOptedOut();
        }

        boolean isPostgres = (mode == MODE_VILLAGE);
        NConfig.set(NConfig.Key.postgres, isPostgres);
        NConfig.set(NConfig.Key.sqlite, !isPostgres);

        if (isPostgres) {
            String host = hostEntry.text().trim();
            int port = parsePort(portEntry.text(), DbSettings.DEFAULT_PORT);
            String db = dbNameEntry.text().trim();
            if (db.isEmpty())
                db = DbSettings.DEFAULT_DATABASE;

            NConfig.set(NConfig.Key.dbHost, host);
            NConfig.set(NConfig.Key.dbPort, port);
            NConfig.set(NConfig.Key.dbName, db);
            NConfig.set(NConfig.Key.dbSsl, DbSettings.normalizeSsl(sslMode));
            NConfig.set(NConfig.Key.dbVillage, villageName);
            NConfig.set(NConfig.Key.serverUser, usernameEntry.text());
            DbCredentials.store(passwordEntry.text());
            /* Kept in step so downgrading to a client that only knows serverNode still connects to
             * the same server rather than to whatever was last there. */
            NConfig.set(NConfig.Key.serverNode, host.isEmpty() ? "" : host + ":" + port);
        } else {
            NConfig.set(NConfig.Key.dbFilePath, filePathEntry.text());
        }

        if (enabled) {
            if (nurgling.NCore.databaseManager != null) {
                nurgling.NCore.databaseManager.reconnect();
            }
            reloadAreasFromDatabase();
            // Fish locations have their own sync worker; make it re-read on its next tick too.
            if (nurgling.NCore.databaseManager != null
                && nurgling.NCore.databaseManager.getFishLocationService() != null) {
                nurgling.NCore.databaseManager.getFishLocationService().requestReload();
            }
        } else if (wasEnabled) {
            reloadAreasFromFile();
        }

        NConfig.needUpdate();
    }

    /** Read a pasted invite into the fields, then connect with them. */
    private void applyInvite() {
        try {
            InviteCode code = InviteCode.decode(inviteEntry.text());
            hostEntry.settext(code.host);
            portEntry.settext(String.valueOf(code.port));
            dbNameEntry.settext(code.database);
            usernameEntry.settext(code.user);
            passwordEntry.settext(code.password);
            sslMode = code.sslmode;
            sslBox.change(sslLabelFor(sslMode));
            if (!code.village.isEmpty())
                villageName = code.village;
            mode = MODE_VILLAGE;
            modes.check(MODE_VILLAGE);
            inviteEntry.settext("");
            updateWidgetsVisibility();
            save();
            probeText = L10n.get("database.invite_applied",
                villageName.isEmpty() ? code.host : villageName);
            probeColor = Color.GREEN;
        } catch (InviteCode.FormatException e) {
            probeText = e.getMessage();
            probeColor = Color.ORANGE;
        }
    }

    /**
     * Show one of the database windows, reusing the one already on screen.
     *
     * <p>Constructing unconditionally stacked a fresh window on every press - and since these
     * windows hide rather than destroy on close, the pile stayed. The supplier means nothing is
     * built, and no database query fires, when one is already open.
     */
    private void openWindow(Class<? extends haven.Window> type,
                            java.util.function.Supplier<? extends haven.Window> make) {
        nurgling.NGameUI gui = NUtils.getGameUI();
        if (gui == null) {
            msg("Log in first - this needs a live connection.", Color.YELLOW);
            return;
        }
        for (Widget w = gui.child; w != null; w = w.next) {
            if (type.isInstance(w)) {
                w.show();
                w.raise();
                return;
            }
        }
        haven.Window win = make.get();
        gui.add(win, new Coord(UI.scale(120), UI.scale(80)));
        win.show();
    }

    /**
     * Open a throwaway connection with whatever is currently typed in, and report the outcome.
     *
     * <p>Runs on its own thread: it can block for the full connect timeout, and the client must not.
     */
    private void runProbe() {
        if (probeRunning)
            return;
        final DbSettings s = new DbSettings(hostEntry.text().trim(),
                                            parsePort(portEntry.text(), DbSettings.DEFAULT_PORT),
                                            dbNameEntry.text().trim().isEmpty()
                                                ? DbSettings.DEFAULT_DATABASE
                                                : dbNameEntry.text().trim(),
                                            DbSettings.normalizeSsl(sslMode),
                                            usernameEntry.text(),
                                            passwordEntry.text());
        probeRunning = true;
        probeText = L10n.get("database.testing");
        probeColor = Color.WHITE;

        Thread worker = new Thread(() -> {
            ConnectionDoctor.Result r = ConnectionDoctor.probe(s);
            if (r.ok()) {
                probeText = L10n.get("database.test_ok", s.describe(), r.serverUser,
                    r.sslInUse ? L10n.get("database.encrypted") : L10n.get("database.not_encrypted"),
                    r.schemaVersion < 0 ? L10n.get("database.schema_missing")
                                        : L10n.get("database.schema_version", r.schemaVersion));
                probeColor = r.sslInUse ? Color.GREEN : Color.YELLOW;
            } else {
                probeText = r.message;
                probeColor = Color.ORANGE;
                System.err.println("[DatabaseSettings] test failed: " + r.problem + " - " + r.detail);
            }
            probeRunning = false;
        }, "DB-Test");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if (!built)
            return;
        String text = probeText;
        if (text != null) {
            // A finished probe outranks the live status until the panel is reopened.
            statusDetail.settext(text);
            statusDetail.setcolor(probeColor);
        } else {
            statusDetail.settext("");
        }
        refreshStatusLine();
    }

    /** The always-on line: what this client is connected to right now, and whether it is encrypted. */
    private void refreshStatusLine() {
        if (mode == MODE_OFF) {
            statusLabel.settext(L10n.get("database.status_off"));
            statusLabel.setcolor(Color.LIGHT_GRAY);
            return;
        }
        if (mode == MODE_SQLITE) {
            statusLabel.settext(L10n.get("database.status_sqlite"));
            statusLabel.setcolor(Color.LIGHT_GRAY);
            return;
        }

        DatabaseManager dbm = nurgling.NCore.databaseManager;
        if (dbm != null && dbm.isReady()) {
            DbSettings s = DbSettings.fromConfig();
            String user = dbm.getConnectedUser();
            String where = villageName.isEmpty() ? s.describe() : villageName;
            String line = L10n.get("database.status_connected", where,
                                   (user == null || user.isEmpty()) ? s.user : user)
                        + " - " + (dbm.isSslInUse() ? L10n.get("database.encrypted")
                                                    : L10n.get("database.not_encrypted"));
            statusLabel.settext(line);
            statusLabel.setcolor(dbm.isSslInUse() ? Color.GREEN : Color.YELLOW);
            return;
        }

        String err = (dbm == null) ? "" : dbm.getConnectionError();
        if (err != null && !err.isEmpty()) {
            statusLabel.settext(L10n.get("database.status_disconnected") + " - " + err);
            statusLabel.setcolor(Color.ORANGE);
        } else {
            statusLabel.settext(L10n.get("database.status_disconnected"));
            statusLabel.setcolor(Color.LIGHT_GRAY);
        }
    }

    private void updateWidgetsVisibility() {
        if (!built)
            return;
        boolean village = (mode == MODE_VILLAGE);
        boolean sqlite = (mode == MODE_SQLITE);

        boolean adv = village && showAdvanced;
        hostLabel.visible = adv;
        hostEntry.visible = adv;
        portLabel.visible = adv;
        portEntry.visible = adv;
        dbNameLabel.visible = adv;
        dbNameEntry.visible = adv;
        userLabel.visible = adv;
        usernameEntry.visible = adv;
        passLabel.visible = adv;
        passwordEntry.visible = adv;
        sslLabel.visible = adv;
        sslBox.visible = adv;

        testButton.visible = village;
        villagersButton.visible = village;
        hostButton.visible = village;
        inviteLabel.visible = village;
        inviteEntry.visible = village;
        inviteApply.visible = village;
        advancedToggle.visible = village;
        advancedToggle.a = showAdvanced;

        int blockHeight = adv ? advancedHeight : (sqlite ? sqliteHeight : 0);
        int shift = advancedHeight - blockHeight;
        for (int i = 0; i < belowBlock.size(); i++)
            belowBlock.get(i).move(belowBase.get(i).add(0, -shift));

        fileLabel.visible = sqlite;
        filePathEntry.visible = sqlite;
        initDbButton.visible = sqlite;

        /* Sharing is only meaningful against a village database, so it is shown rather than
         * silently ignored - the old panel offered all four whatever the mode. */
        sharingLabel.visible = village;
        shareHsCheckbox.visible = village;
        shareMapMarksCheckbox.visible = village;
        sharePosCheckbox.visible = village;
        showPeerPosCheckbox.visible = village;
        seedFishButton.visible = village;
    }

    private void createSqliteDatabase() {
        java.awt.EventQueue.invokeLater(() -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("SQLite Database", "db"));
            if (fc.showSaveDialog(null) != JFileChooser.APPROVE_OPTION)
                return;

            String dbPathLocal = fc.getSelectedFile().getAbsolutePath();
            if (!dbPathLocal.endsWith(".db")) {
                dbPathLocal += ".db";
            }

            try {
                Files.deleteIfExists(Paths.get(dbPathLocal));
                /* Only the file has to exist here. Every table it needs is created by the migration
                 * pass on first connect, the same way a PostgreSQL database gets its schema - there
                 * is one definition of the schema and it lives in MigrationManager. */
                Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPathLocal);
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("PRAGMA user_version = 0");
                }
                conn.close();

                filePathEntry.settext(dbPathLocal);
                msg("Database file created; tables are set up on connect", Color.YELLOW);
            } catch (java.sql.SQLException | java.io.IOException e) {
                msg("Failed to create database: " + e.getMessage(), Color.RED);
                e.printStackTrace();
            }
        });
    }

    /**
     * Reload areas from database after DB settings change
     */
    private void reloadAreasFromDatabase() {
        if (ui == null || nurgling.NUtils.getGameUI() == null ||
            nurgling.NUtils.getGameUI().map == null) {
            return;
        }

        try {
            // Sync owns the bulk load now. Reset firstPollDone so the next
            // tick (which is at most a few seconds away) re-runs loadAreas
            // and replaces the local map via onFullSync.
            if (nurgling.NCore.databaseManager != null
                && nurgling.NCore.databaseManager.getAreaService() != null) {
                nurgling.NCore.databaseManager.getAreaService().requestReload();
                System.out.println("Areas reload requested; sync will refresh local map shortly");
            } else {
                // DB not yet initialized: clear the flag so loadAreasIfNeeded
                // can be retried by whatever wakes the sync.
                nurgling.NUtils.getGameUI().map.glob.map.areasLoaded = false;
                nurgling.NUtils.getGameUI().map.glob.map.loadAreasIfNeeded();
            }
            refreshAreasUI();
        } catch (Exception e) {
            System.err.println("Failed to reload areas from database: " + e.getMessage());
        }
    }

    /**
     * Reload areas from file after DB is disabled
     */
    private void reloadAreasFromFile() {
        if (ui == null || nurgling.NUtils.getGameUI() == null ||
            nurgling.NUtils.getGameUI().map == null) {
            return;
        }

        try {
            nurgling.NUtils.getGameUI().map.glob.map.areas.clear();
            nurgling.NUtils.getGameUI().map.glob.map.areasLoaded = false;
            nurgling.NUtils.getGameUI().map.glob.map.loadAreasIfNeeded();
            refreshAreasUI();
            System.out.println("Areas reloaded from file");
        } catch (Exception e) {
            System.err.println("Failed to reload areas from file: " + e.getMessage());
        }
    }

    /**
     * Refresh areas display (overlays and widget)
     */
    private void refreshAreasUI() {
        try {
            if (nurgling.NUtils.getGameUI() == null || nurgling.NUtils.getGameUI().map == null) {
                return;
            }

            nurgling.NMapView map = (nurgling.NMapView) nurgling.NUtils.getGameUI().map;

            if (map.nols != null) {
                for (nurgling.overlays.map.NOverlay overlay : map.nols.values()) {
                    if (overlay != null) {
                        overlay.requpdate2 = true;
                    }
                }
            }

            if (nurgling.NUtils.getGameUI().areas != null &&
                nurgling.NUtils.getGameUI().areas.al != null) {
                nurgling.NUtils.getGameUI().areas.showPath(nurgling.NUtils.getGameUI().areas.currentPath);
            }
        } catch (Exception e) {
            // Ignore UI refresh errors
        }
    }

    /** Settings can be opened from the login screen, where there is no game UI to talk to. */
    private static void msg(String text, Color color) {
        nurgling.NGameUI gui = NUtils.getGameUI();
        if (gui != null) {
            gui.msg(text, color);
        } else {
            System.out.println("[DatabaseSettings] " + text);
        }
    }

    private static int parsePort(String text, int fallback) {
        try {
            int v = Integer.parseInt(text.trim());
            return (v > 0 && v <= 65535) ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean getBool(NConfig.Key key) {
        Object val = NConfig.get(key);
        return val instanceof Boolean ? (Boolean) val : false;
    }

    private String asString(Object v) {
        return v == null ? "" : v.toString();
    }

    /**
     * Import this world's fish location file into the database.
     *
     * <p>Reads the file rather than the in-memory map: in database mode that map already holds database
     * rows, and the point of the action is to bring across what the file still has.
     */
    private void seedFishLocations() {
        nurgling.NGameUI gui = nurgling.NUtils.getGameUI();
        if (gui == null || gui.fishLocationService == null) return;

        if (!getBool(NConfig.Key.ndbenable) || nurgling.NCore.databaseManager == null
            || !nurgling.NCore.databaseManager.isReady()) {
            gui.msg(L10n.get("database.seed_fish_need_db"), Color.YELLOW);
            return;
        }

        nurgling.db.service.FishLocationSeeder seeder =
            nurgling.NCore.databaseManager.getFishLocationSeeder();
        if (seeder == null) {
            // The optional migration that creates the table was refused on this database.
            gui.msg(L10n.get("database.seed_fish_unavailable"), Color.ORANGE);
            return;
        }

        final String dataFile = gui.fishLocationService.getDataFile();
        final String profile = gui.fishLocationService.profile();

        seeder.seedAsync(gui, dataFile, profile)
            .thenAccept(r -> {
                gui.msg(L10n.get("database.seed_fish_result",
                    r.inserted, r.alreadyPresent, r.refreshed, r.unresolvable, r.skippedDeleted),
                    Color.GREEN);
                // Pull the new rows into the live map instead of waiting for them to trickle in.
                if (nurgling.NCore.databaseManager != null
                    && nurgling.NCore.databaseManager.getFishLocationService() != null) {
                    nurgling.NCore.databaseManager.getFishLocationService().requestReload();
                }
            })
            .exceptionally(e -> {
                gui.msg(L10n.get("database.seed_fish_failed", String.valueOf(e.getMessage())), Color.RED);
                return null;
            });
    }
}
