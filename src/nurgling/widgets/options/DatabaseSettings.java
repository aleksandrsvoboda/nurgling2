package nurgling.widgets.options;

import haven.*;
import haven.Button;
import haven.Label;
import nurgling.NConfig;
import nurgling.NUtils;
import nurgling.i18n.L10n;
import nurgling.widgets.nsettings.Panel;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedList;

public class DatabaseSettings extends Panel {
    private Widget prev;
    private TextEntry hostEntry, usernameEntry, passwordEntry;
    private TextEntry filePathEntry;
    private Label hostLabel, userLabel, passLabel, fileLabel;
    private Button initDbButton;
    private Label portLabel;
    private TextEntry portEntry;
    private Label connLabel;
    private TextEntry connEntry;
    private Button connApply;
    private Button villagersButton;
    private DbSizeView sizeView;
    private Scrollport scrollport;
    private Widget content;
    /* Where the disk-usage section sits in each mode: the widget above it differs, and a
     * fixed position would leave a band of empty panel in whichever mode is not PostgreSQL. */
    private int sizeYPostgres, sizeYSqlite;
    private Button seedFishButton;
    private CheckBox enableCheckbox;
    private CheckBox shareHsCheckbox;
    private CheckBox shareMapMarksCheckbox;
    private CheckBox sharePosCheckbox;
    private CheckBox showPeerPosCheckbox;
    private Dropbox<String> dbType;
    private final int labelWidth = UI.scale(80); // РЁРёСЂРёРЅР° Р»РµР№Р±Р»РѕРІ
    private final int entryX = UI.scale(110);    // X-РєРѕРѕСЂРґРёРЅР°С‚Р° РґР»СЏ TextEntry (was 90, increased for better space)
    private final int margin = UI.scale(10);

    private boolean enabled;
    private boolean shareHs;
    private boolean shareMapMarks;
    private boolean sharePos;
    private boolean showPeerPos;
    private String dbTypeStr;
    private String host, user, pass, dbPath;

    public DatabaseSettings() {
        super("");
        int y = margin;

        /* Every other settings page with more than a screenful does this, and this one has more
         * than a screenful now: the disk-usage section below used to be squeezed into whatever
         * pixels the connection fields left over, which on a 19-table database meant one bar and a
         * "18 more" row. Inside a scrollport it can be as tall as it has tables. */
        scrollport = add(new Scrollport(new Coord(UI.scale(570), UI.scale(550))), Coord.z);
        content = new Widget(new Coord(UI.scale(550), UI.scale(50))) {
            @Override
            public void pack() {
                resize(contentsz());
            }

            /* A child that grows after the fact - which is what the disk-usage section does when a
             * measurement lands, seconds after this page was opened - has to move the scrollbar
             * with it, or the rows it just gained cannot be reached. */
            @Override
            public void cresize(Widget ch) {
                pack();
                scrollport.cont.update();
            }
        };
        scrollport.cont.add(content, Coord.z);
        /* Match the panel to the scrollport. Panel's own size is 580 tall, and nothing shrinks it
         * to its contents any more - left at that it would reach down over the settings window's
         * Save and Cancel buttons. */
        resize(new Coord(UI.scale(570), UI.scale(550)));

        // Р§РµРєР±РѕРєСЃ РІРєР»СЋС‡РµРЅРёСЏ/РІС‹РєР»СЋС‡РµРЅРёСЏ Р±Р°Р·С‹ РґР°РЅРЅС‹С…
        prev = enableCheckbox = content.add(new CheckBox(L10n.get("database.enable")) {
            public void set(boolean val) {
                a = val;
                enabled = val;
                updateWidgetsVisibility();
            }
        }, new Coord(margin, y));
        y += enableCheckbox.sz.y + UI.scale(5);

        // Publish this character's hearth secret to the shared database
        prev = shareHsCheckbox = content.add(new CheckBox(L10n.get("database.share_hearth_secret")) {
            public void set(boolean val) {
                a = val;
                shareHs = val;
            }
        }, new Coord(margin, y));
        shareHsCheckbox.tooltip = Text.render(L10n.get("database.share_hearth_secret_tip")).tex();
        y += shareHsCheckbox.sz.y + UI.scale(5);

        // Whether the map window's database buttons carry markers as well as terrain
        prev = shareMapMarksCheckbox = content.add(new CheckBox(L10n.get("database.share_map_markers")) {
            public void set(boolean val) {
                a = val;
                shareMapMarks = val;
            }
        }, new Coord(margin, y));
        shareMapMarksCheckbox.tooltip = Text.render(L10n.get("database.share_map_markers_tip")).tex();
        y += shareMapMarksCheckbox.sz.y + UI.scale(5);

        // Publish this character's position so everyone on this database can see it, at any distance
        prev = sharePosCheckbox = content.add(new CheckBox(L10n.get("database.share_position")) {
            public void set(boolean val) {
                a = val;
                sharePos = val;
            }
        }, new Coord(margin, y));
        sharePosCheckbox.tooltip = Text.render(L10n.get("database.share_position_tip")).tex();
        y += sharePosCheckbox.sz.y + UI.scale(5);

        // Whether other people's published positions are drawn on this client's maps
        prev = showPeerPosCheckbox = content.add(new CheckBox(L10n.get("database.show_peer_positions")) {
            public void set(boolean val) {
                a = val;
                showPeerPos = val;
            }
        }, new Coord(margin, y));
        showPeerPosCheckbox.tooltip = Text.render(L10n.get("database.show_peer_positions_tip")).tex();
        y += showPeerPosCheckbox.sz.y + UI.scale(8);

        // Р—Р°РіРѕР»РѕРІРѕРє СЂР°Р·РґРµР»Р°
        prev = content.add(new Label(L10n.get("database.settings")), new Coord(margin, y));
        y += prev.sz.y + UI.scale(5);

        // Р’С‹РїР°РґР°СЋС‰РёР№ СЃРїРёСЃРѕРє РґР»СЏ РІС‹Р±РѕСЂР° С‚РёРїР° Р±Р°Р·С‹ РґР°РЅРЅС‹С…
        prev = content.add(new Label(L10n.get("database.type")), new Coord(margin, y));
        dbType = content.add(new Dropbox<String>(UI.scale(150), 5, UI.scale(16)) {
            @Override
            protected String listitem(int i) {
                return new LinkedList<>(getDbTypes()).get(i);
            }

            @Override
            protected int listitems() {
                return getDbTypes().size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }

            @Override
            public void change(String item) {
                super.change(item);
                dbTypeStr = item;
                updateWidgetsVisibility();
            }
        }, new Coord(entryX, y));
        y += dbType.sz.y + UI.scale(10);

        int firstSettingY = y;

        // РЎРѕР·РґР°РµРј РІРёРґР¶РµС‚С‹ РґР»СЏ PostgreSQL
        hostLabel = content.add(new Label(L10n.get("database.host")), new Coord(margin, firstSettingY));
        hostEntry = content.add(new TextEntry(UI.scale(150), ""), new Coord(entryX, firstSettingY));
        y += hostEntry.sz.y + UI.scale(5);

        /* Its own box rather than hidden inside the host string. It is still stored as one value -
         * splitting the storage would need a default for the port, and a default is what quietly
         * moves a village off the port it has always used. */
        portLabel = content.add(new Label(L10n.get("database.port")), new Coord(margin, y));
        portEntry = content.add(new TextEntry(UI.scale(70), ""), new Coord(entryX, y));
        portEntry.tooltip = Text.render(L10n.get("database.port_tip")).tex();
        y += portEntry.sz.y + UI.scale(5);

        userLabel = content.add(new Label(L10n.get("database.username")), new Coord(margin, y));
        usernameEntry = content.add(new TextEntry(UI.scale(150), ""), new Coord(entryX, y));
        y += usernameEntry.sz.y + UI.scale(5);

        passLabel = content.add(new Label(L10n.get("database.password")), new Coord(margin, y));
        passwordEntry = content.add(new TextEntry(UI.scale(150), ""), new Coord(entryX, y));
        passwordEntry.pw = true;
        y += passwordEntry.sz.y + UI.scale(10);

        // РЎРѕР·РґР°РµРј РІРёРґР¶РµС‚С‹ РґР»СЏ SQLite
        fileLabel = content.add(new Label(L10n.get("database.filepath")), new Coord(margin, firstSettingY));
        filePathEntry = content.add(new TextEntry(UI.scale(150), ""), new Coord(entryX, firstSettingY));
        y += filePathEntry.sz.y + UI.scale(5);

        // РљРЅРѕРїРєР° РёРЅРёС†РёР°Р»РёР·Р°С†РёРё РЅРѕРІРѕР№ Р±Р°Р·С‹ РґР°РЅРЅС‹С…
        initDbButton = content.add(new Button(UI.scale(200), L10n.get("database.init_new")) {
            @Override
            public void click() {
                super.click();
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
                        /* Only the file has to exist. Every table it needs is created by the
                         * migration pass on first connect, exactly as it is for PostgreSQL - the
                         * schema is defined in MigrationManager and nowhere else, so this cannot
                         * drift out of step with it the way the old copy here did. */
                        Files.deleteIfExists(Paths.get(dbPathLocal));
                        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPathLocal);
                        conn.close();

                        filePathEntry.settext(dbPathLocal);
                        dbPath = dbPathLocal;
                        NUtils.getGameUI().msg("Database file created; tables are set up on connect",
                            Color.YELLOW);
                    } catch (Exception e) {
                        NUtils.getGameUI().msg("Failed to create database: " + e.getMessage(), Color.RED);
                        e.printStackTrace();
                    }
                });
            }
        }, new Coord(margin, firstSettingY + filePathEntry.sz.y + UI.scale(5)));

        y += UI.scale(10);

        /* The one bridge between the JSON file and the database. Fish locations are file OR database -
         * nothing crosses automatically - so this is how spots saved before the database existed get
         * carried over. Idempotent, because a row id is derived from the spot's position and fish. */
        seedFishButton = content.add(new Button(UI.scale(200), L10n.get("database.seed_fish")) {
            @Override
            public void click() {
                super.click();
                seedFishLocations();
            }
        }, new Coord(margin, y));
        seedFishButton.tooltip = Text.render(L10n.get("database.seed_fish_tip")).tex();
        y += seedFishButton.sz.y + UI.scale(12);

        /* One field instead of three. The host field above is spliced straight into the JDBC URL,
         * so it silently needs "host:port" - a connection string carries the port with it, which is
         * the part that otherwise gets lost between the admin's chat message and this panel. */
        connLabel = content.add(new Label(L10n.get("database.connstring")), new Coord(margin, y + UI.scale(3)));
        connEntry = content.add(new TextEntry(UI.scale(250), ""), new Coord(UI.scale(130), y));
        connApply = content.add(new Button(UI.scale(90), L10n.get("database.connstring_apply")) {
            @Override
            public void click() {
                super.click();
                applyConnectionString();
            }
        }, new Coord(UI.scale(390), y));
        connApply.tooltip = Text.render(L10n.get("database.connstring_tip")).tex();
        y += connEntry.sz.y + UI.scale(10);

        villagersButton = content.add(new Button(UI.scale(200), L10n.get("database.villagers")) {
            @Override
            public void click() {
                super.click();
                openVillagers();
            }
        }, new Coord(margin, y));
        villagersButton.tooltip = Text.render(L10n.get("database.villagers_tip")).tex();

        sizeYPostgres = y + villagersButton.sz.y + UI.scale(12);
        sizeYSqlite = seedFishButton.c.y + seedFishButton.sz.y + UI.scale(12);
        sizeView = content.add(new DbSizeView(UI.scale(470)), new Coord(margin, sizeYPostgres));

        load();
        updateWidgetsVisibility();
    }

    @Override
    public void load() {
        enabled = getBool(NConfig.Key.ndbenable);
        enableCheckbox.a = enabled;
        shareHs = getBool(NConfig.Key.shareHearthSecret);
        shareHsCheckbox.a = shareHs;
        shareMapMarks = getBool(NConfig.Key.mapShareMarkers);
        shareMapMarksCheckbox.a = shareMapMarks;
        sharePos = getBool(NConfig.Key.sharePosition);
        sharePosCheckbox.a = sharePos;
        showPeerPos = getBool(NConfig.Key.showPeerPositions);
        showPeerPosCheckbox.a = showPeerPos;

        boolean isPostgres = getBool(NConfig.Key.postgres);
        dbTypeStr = isPostgres ? "PostgreSQL" : "SQLite";
        dbType.change(dbTypeStr);

        host = asString(NConfig.get(NConfig.Key.serverNode));
        String[] hp = nurgling.db.ConnectionString.splitNode(host);
        user = asString(NConfig.get(NConfig.Key.serverUser));
        pass = asString(NConfig.get(NConfig.Key.serverPass));
        dbPath = asString(NConfig.get(NConfig.Key.dbFilePath));

        hostEntry.settext(hp[0]);
        portEntry.settext(hp[1]);
        usernameEntry.settext(user);
        passwordEntry.settext(pass);
        filePathEntry.settext(dbPath);

        updateWidgetsVisibility();

        /* The panel is shown again every time the player clicks another settings page and comes
         * back, so this asks only when the last figure has gone stale. */
        if (sizeView != null && enabled) {
            sizeView.refreshIfStale();
        }
    }

    @Override
    public void save() {
        boolean wasEnabled = (Boolean) NConfig.get(NConfig.Key.ndbenable);
        
        NConfig.set(NConfig.Key.ndbenable, enabled);
        NConfig.set(NConfig.Key.shareHearthSecret, shareHs);
        NConfig.set(NConfig.Key.mapShareMarkers, shareMapMarks);

        /* Turning sharing off has to take the row out of the database, not merely stop refreshing
         * it: otherwise this character keeps showing on everyone's map until it ages out, which is
         * exactly the surprise an opt-out is there to prevent. */
        boolean wasSharing = (Boolean) NConfig.get(NConfig.Key.sharePosition);
        NConfig.set(NConfig.Key.sharePosition, sharePos);
        NConfig.set(NConfig.Key.showPeerPositions, showPeerPos);
        if (wasSharing && !sharePos && nurgling.NCore.databaseManager != null
            && nurgling.NCore.databaseManager.getPeerPositionService() != null) {
            nurgling.NCore.databaseManager.getPeerPositionService().withdrawOptedOut();
        }
        boolean isPostgres = "PostgreSQL".equals(dbTypeStr);
        NConfig.set(NConfig.Key.postgres, isPostgres);
        NConfig.set(NConfig.Key.sqlite, !isPostgres);

        if (isPostgres) {
            NConfig.set(NConfig.Key.serverNode,
                nurgling.db.ConnectionString.joinNode(hostEntry.text(), portEntry.text()));
            NConfig.set(NConfig.Key.serverUser, usernameEntry.text());
            NConfig.set(NConfig.Key.serverPass, passwordEntry.text());
        } else {
            NConfig.set(NConfig.Key.dbFilePath, filePathEntry.text());
        }

        // Handle database manager and areas reload
        if (enabled) {
            // DB is being enabled or settings changed - reconnect and reload areas from DB
            if (nurgling.NCore.databaseManager != null) {
                nurgling.NCore.databaseManager.reconnect();
            }
            // Reload areas from database
            reloadAreasFromDatabase();
            // Fish locations have their own sync worker; make it re-read on its next tick too.
            if (nurgling.NCore.databaseManager != null
                && nurgling.NCore.databaseManager.getFishLocationService() != null) {
                nurgling.NCore.databaseManager.getFishLocationService().requestReload();
            }
        } else if (wasEnabled) {
            // DB was enabled but now disabled - reload areas from file
            reloadAreasFromFile();
        }

        /* Pointed at a different database, or at none: whatever was measured describes the old
         * one. Dropping it also makes the next open re-measure rather than show a stale figure. */
        if (sizeView != null) {
            sizeView.invalidate();
        }

        NConfig.needUpdate();
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
            // Clear current areas
            nurgling.NUtils.getGameUI().map.glob.map.areas.clear();
            // Reset loaded flag to force reload
            nurgling.NUtils.getGameUI().map.glob.map.areasLoaded = false;
            // Trigger reload (will load from file since DB is disabled)
            nurgling.NUtils.getGameUI().map.glob.map.loadAreasIfNeeded();
            // Refresh UI
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
            
            // Force redraw of all area overlays
            if (map.nols != null) {
                for (nurgling.overlays.map.NOverlay overlay : map.nols.values()) {
                    if (overlay != null) {
                        overlay.requpdate2 = true;
                    }
                }
            }
            
            // Refresh NAreasWidget if open
            if (nurgling.NUtils.getGameUI().areas != null && 
                nurgling.NUtils.getGameUI().areas.al != null) {
                nurgling.NUtils.getGameUI().areas.showPath(nurgling.NUtils.getGameUI().areas.currentPath);
            }
        } catch (Exception e) {
            // Ignore UI refresh errors
        }
    }

    private void updateWidgetsVisibility() {
        boolean isEnabled = enabled;
        boolean isPostgres = isEnabled && "PostgreSQL".equals(dbTypeStr);
        boolean isSQLite = isEnabled && !isPostgres;

        if (hostLabel != null) {
            // РЈРїСЂР°РІР»СЏРµРј РІРёРґРёРјРѕСЃС‚СЊСЋ РІСЃРµС… СЌР»РµРјРµРЅС‚РѕРІ РІ Р·Р°РІРёСЃРёРјРѕСЃС‚Рё РѕС‚ РІРєР»СЋС‡РµРЅРёСЏ Р±Р°Р·С‹ РґР°РЅРЅС‹С…
            hostLabel.visible = isPostgres;
            hostEntry.visible = isPostgres;
            portLabel.visible = isPostgres;
            portEntry.visible = isPostgres;
            userLabel.visible = isPostgres;
            usernameEntry.visible = isPostgres;
            passLabel.visible = isPostgres;
            passwordEntry.visible = isPostgres;

            fileLabel.visible = isSQLite;
            filePathEntry.visible = isSQLite;
            initDbButton.visible = isSQLite;

            connLabel.visible = isPostgres;
            connEntry.visible = isPostgres;
            connApply.visible = isPostgres;
            villagersButton.visible = isPostgres;
            // Don't reconnect here - it's just visibility update, not settings change
        }

        if (sizeView != null) {
            sizeView.visible = isEnabled;
            sizeView.move(new Coord(margin, isPostgres ? sizeYPostgres : sizeYSqlite));
        }

        /* The panel itself keeps its fixed size - the scrollport fills it. Only the content
         * inside is re-measured, because hiding half the fields in one mode genuinely does make it
         * shorter, and the scrollbar has to follow. */
        if (content != null) {
            content.pack();
            scrollport.cont.update();
        }
    }

    /**
     * Fill the connection fields from a pasted {@code postgresql://} string, then save.
     *
     * <p>Saving straight away rather than leaving it for the OK button: pasting a connection string
     * is a complete instruction, and the panel's own save path is what reconnects and reloads areas.
     */
    private void applyConnectionString() {
        try {
            nurgling.db.ConnectionString cs =
                nurgling.db.ConnectionString.parse(connEntry.text());
            String[] parts = nurgling.db.ConnectionString.splitNode(cs.node);
            hostEntry.settext(parts[0]);
            portEntry.settext(parts[1]);
            usernameEntry.settext(cs.user);
            passwordEntry.settext(cs.password);
            connEntry.settext("");

            /* The database name is a constant in the JDBC URL, so a string naming a different one
             * would connect somewhere the user did not ask for. Say so rather than ignore it. */
            if (!cs.database.isEmpty()
                && !cs.database.equals(nurgling.db.ConnectionString.DEFAULT_DATABASE)) {
                msg(L10n.get("database.connstring_dbname",
                    cs.database, nurgling.db.ConnectionString.DEFAULT_DATABASE), Color.ORANGE);
            }
            save();
            msg(L10n.get("database.connstring_applied", cs.user, cs.node), Color.GREEN);
        } catch (nurgling.db.ConnectionString.FormatException e) {
            msg(e.getMessage(), Color.ORANGE);
        }
    }

    private void openVillagers() {
        nurgling.NGameUI gui = NUtils.getGameUI();
        if (gui == null) {
            return;
        }
        /* Reuse whatever is already on screen: the window hides rather than destroys on close, so
         * constructing one per press would stack them up invisibly. */
        for (Widget w = gui.child; w != null; w = w.next) {
            if (w instanceof nurgling.widgets.db.VillagersWindow) {
                w.show();
                w.raise();
                return;
            }
        }
        nurgling.widgets.db.VillagersWindow win = new nurgling.widgets.db.VillagersWindow();
        gui.add(win, new Coord(UI.scale(120), UI.scale(80)));
        win.show();
    }

    /** Settings can be opened before login, where there is no game UI to talk to. */
    private static void msg(String text, Color color) {
        nurgling.NGameUI gui = NUtils.getGameUI();
        if (gui != null) {
            gui.msg(text, color);
        } else {
            System.out.println("[DatabaseSettings] " + text);
        }
    }

    private LinkedList<String> getDbTypes() {
        LinkedList<String> types = new LinkedList<>();
        types.add("PostgreSQL");
        types.add("SQLite");
        return types;
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
