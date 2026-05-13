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
import java.sql.Statement;
import java.util.LinkedList;

public class DatabaseSettings extends Panel {
    private Widget prev;
    private TextEntry hostEntry, usernameEntry, passwordEntry;
    private TextEntry filePathEntry;
    private Label hostLabel, userLabel, passLabel, fileLabel;
    private Button initDbButton;
    private CheckBox enableCheckbox;
    private Dropbox<String> dbType;
    private final int labelWidth = UI.scale(80); // РЁРёСЂРёРЅР° Р»РµР№Р±Р»РѕРІ
    private final int entryX = UI.scale(110);    // X-РєРѕРѕСЂРґРёРЅР°С‚Р° РґР»СЏ TextEntry (was 90, increased for better space)
    private final int margin = UI.scale(10);

    private boolean enabled;
    private String dbTypeStr;
    private String host, user, pass, dbPath;

    public DatabaseSettings() {
        super("");
        int y = margin;

        // Р§РµРєР±РѕРєСЃ РІРєР»СЋС‡РµРЅРёСЏ/РІС‹РєР»СЋС‡РµРЅРёСЏ Р±Р°Р·С‹ РґР°РЅРЅС‹С…
        prev = enableCheckbox = add(new CheckBox(L10n.get("database.enable")) {
            public void set(boolean val) {
                a = val;
                enabled = val;
                updateWidgetsVisibility();
            }
        }, new Coord(margin, y));
        y += enableCheckbox.sz.y + UI.scale(8);

        // Р—Р°РіРѕР»РѕРІРѕРє СЂР°Р·РґРµР»Р°
        prev = add(new Label(L10n.get("database.settings")), new Coord(margin, y));
        y += prev.sz.y + UI.scale(5);

        // Р’С‹РїР°РґР°СЋС‰РёР№ СЃРїРёСЃРѕРє РґР»СЏ РІС‹Р±РѕСЂР° С‚РёРїР° Р±Р°Р·С‹ РґР°РЅРЅС‹С…
        prev = add(new Label(L10n.get("database.type")), new Coord(margin, y));
        dbType = add(new Dropbox<String>(UI.scale(150), 5, UI.scale(16)) {
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
        hostLabel = add(new Label(L10n.get("database.host")), new Coord(margin, firstSettingY));
        hostEntry = add(new TextEntry(UI.scale(150), ""), new Coord(entryX, firstSettingY));
        y += hostEntry.sz.y + UI.scale(5);

        userLabel = add(new Label(L10n.get("database.username")), new Coord(margin, y));
        usernameEntry = add(new TextEntry(UI.scale(150), ""), new Coord(entryX, y));
        y += usernameEntry.sz.y + UI.scale(5);

        passLabel = add(new Label(L10n.get("database.password")), new Coord(margin, y));
        passwordEntry = add(new TextEntry(UI.scale(150), ""), new Coord(entryX, y));
        passwordEntry.pw = true;
        y += passwordEntry.sz.y + UI.scale(10);

        // РЎРѕР·РґР°РµРј РІРёРґР¶РµС‚С‹ РґР»СЏ SQLite
        fileLabel = add(new Label(L10n.get("database.filepath")), new Coord(margin, firstSettingY));
        filePathEntry = add(new TextEntry(UI.scale(150), ""), new Coord(entryX, firstSettingY));
        y += filePathEntry.sz.y + UI.scale(5);

        // РљРЅРѕРїРєР° РёРЅРёС†РёР°Р»РёР·Р°С†РёРё РЅРѕРІРѕР№ Р±Р°Р·С‹ РґР°РЅРЅС‹С…
        initDbButton = add(new Button(UI.scale(200), L10n.get("database.init_new")) {
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
                        // РЎРѕР·РґР°РµРј РЅРѕРІСѓСЋ Р±Р°Р·Сѓ РґР°РЅРЅС‹С…
                        Files.deleteIfExists(Paths.get(dbPathLocal));
                        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPathLocal);

                        // РРЅРёС†РёР°Р»РёР·РёСЂСѓРµРј С‚Р°Р±Р»РёС†С‹
                        try (Statement stmt = conn.createStatement()) {
                            stmt.executeUpdate("CREATE TABLE recipes (" +
                                    "recipe_hash VARCHAR(64) PRIMARY KEY, " +
                                    "item_name VARCHAR(255) NOT NULL, " +
                                    "resource_name VARCHAR(255) NOT NULL, " +
                                    "hunger FLOAT NOT NULL, " +
                                    "energy INT NOT NULL)");

                            stmt.executeUpdate("CREATE TABLE ingredients (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                                    "recipe_hash VARCHAR(64) REFERENCES recipes (recipe_hash) ON DELETE CASCADE, " +
                                    "name VARCHAR(255) NOT NULL, " +
                                    "percentage FLOAT NOT NULL, " +
                                    "resource_name VARCHAR(512))");

                            stmt.executeUpdate("CREATE TABLE feps (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                                    "recipe_hash VARCHAR(64) REFERENCES recipes (recipe_hash) ON DELETE CASCADE, " +
                                    "name VARCHAR(255) NOT NULL, " +
                                    "value FLOAT NOT NULL, " +
                                    "weight FLOAT NOT NULL)");

                            stmt.executeUpdate("CREATE TABLE containers (" +
                                    "hash VARCHAR(64) PRIMARY KEY, " +
                                    "grid_id BIGINT, " +
                                    "coord VARCHAR(255))");

                            stmt.executeUpdate("CREATE TABLE storageitems (" +
                                    "item_hash VARCHAR(64) PRIMARY KEY, " +
                                    "name VARCHAR(255) NOT NULL, " +
                                    "quality DOUBLE PRECISION, " +
                                    "coordinates VARCHAR(255), " +
                                    "container VARCHAR(64) NOT NULL)");
                        }

                        conn.close();

                        // РЈСЃС‚Р°РЅР°РІР»РёРІР°РµРј РїСѓС‚СЊ РІ С‚РµРєСЃС‚РѕРІРѕРµ РїРѕР»Рµ
                        filePathEntry.settext(dbPathLocal);
                        dbPath = dbPathLocal;
                        NUtils.getGameUI().msg("Database successfully created and initialized", Color.YELLOW);
                    } catch (Exception e) {
                        NUtils.getGameUI().msg("Failed to create database: " + e.getMessage(), Color.RED);
                        e.printStackTrace();
                    }
                });
            }
        }, new Coord(margin, firstSettingY + filePathEntry.sz.y + UI.scale(5)));

        load();
        updateWidgetsVisibility();
    }

    @Override
    public void load() {
        enabled = getBool(NConfig.Key.ndbenable);
        enableCheckbox.a = enabled;

        boolean isPostgres = getBool(NConfig.Key.postgres);
        dbTypeStr = isPostgres ? "PostgreSQL" : "SQLite";
        dbType.change(dbTypeStr);

        host = asString(NConfig.get(NConfig.Key.serverNode));
        user = asString(NConfig.get(NConfig.Key.serverUser));
        pass = asString(NConfig.get(NConfig.Key.serverPass));
        dbPath = asString(NConfig.get(NConfig.Key.dbFilePath));

        hostEntry.settext(host);
        usernameEntry.settext(user);
        passwordEntry.settext(pass);
        filePathEntry.settext(dbPath);

        updateWidgetsVisibility();
    }

    @Override
    public void save() {
        boolean wasEnabled = (Boolean) NConfig.get(NConfig.Key.ndbenable);
        
        NConfig.set(NConfig.Key.ndbenable, enabled);
        boolean isPostgres = "PostgreSQL".equals(dbTypeStr);
        NConfig.set(NConfig.Key.postgres, isPostgres);
        NConfig.set(NConfig.Key.sqlite, !isPostgres);

        if (isPostgres) {
            NConfig.set(NConfig.Key.serverNode, hostEntry.text());
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
        } else if (wasEnabled) {
            // DB was enabled but now disabled - reload areas from file
            reloadAreasFromFile();
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
            userLabel.visible = isPostgres;
            usernameEntry.visible = isPostgres;
            passLabel.visible = isPostgres;
            passwordEntry.visible = isPostgres;

            fileLabel.visible = isSQLite;
            filePathEntry.visible = isSQLite;
            initDbButton.visible = isSQLite;
            // Don't reconnect here - it's just visibility update, not settings change
        }

        // РџРµСЂРµСѓРїР°РєРѕРІС‹РІР°РµРј РІРёРґР¶РµС‚
        pack();
        sz.y = UI.scale(200);
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
}
