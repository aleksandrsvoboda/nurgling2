package nurgling.widgets.bots;

import haven.*;
import nurgling.NUtils;
import nurgling.actions.bots.BuildCatalog;
import org.json.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * HUD for the world blueprint editor. Holds the current command (what the bot should do
 * next), the currently selected building type, and updates the rotation display.
 */
public class WorldBlueprintEditor extends Window implements Checkable {

    public enum Command {
        NONE,
        SELECT_TYPE,
        SAVE,
        LOAD,
        CLEAR,
        BUILD,
        EXIT
    }

    private Command command = Command.NONE;
    private boolean ready = false;

    private String selectedType = null;
    private int displayRotation = 0;
    private int ghostCount = 0;
    private String loadTarget = null;
    private String saveName = null;

    private Label statusLabel;
    private Label rotationLabel;
    private Dropbox<String> blueprintDropdown;
    private List<String> blueprintNames = new ArrayList<>();

    public WorldBlueprintEditor() {
        super(new Coord(290, 540), "World Blueprint Editor");

        Widget prev;

        prev = add(new Label("Click a building, then click the world to place"),
                   new Coord(UI.scale(10), UI.scale(10)));

        // Building palette
        prev = add(new Label("Buildings:"), prev.pos("bl").add(UI.scale(0, 10)));
        BuildingPalette palette = new BuildingPalette(UI.scale(new Coord(270, 250)));
        prev = add(palette, prev.pos("bl").add(UI.scale(0, 5)));

        // Status & rotation
        statusLabel = add(new Label("Selected: (none)"), prev.pos("bl").add(UI.scale(0, 10)));
        rotationLabel = add(new Label("Rotation: 0°  (wheel to rotate)"),
                            statusLabel.pos("bl").add(UI.scale(0, 5)));

        // Blueprint dropdown
        prev = add(new Label("Saved blueprints:"), rotationLabel.pos("bl").add(UI.scale(0, 12)));

        loadBlueprintNames();
        prev = add(blueprintDropdown = new Dropbox<String>(UI.scale(230), Math.max(3, blueprintNames.size()), UI.scale(16)) {
            @Override
            protected String listitem(int i) {
                return i < blueprintNames.size() ? blueprintNames.get(i) : "";
            }

            @Override
            protected int listitems() { return blueprintNames.size(); }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item != null ? item : "(none)", Coord.z);
            }

            @Override
            public void change(String item) {
                super.change(item);
                loadTarget = item;
            }
        }, prev.pos("bl").add(UI.scale(0, 5)));

        prev = add(new Button(UI.scale(110), "Load") {
            @Override
            public void click() {
                super.click();
                if (loadTarget == null || loadTarget.isEmpty()) {
                    NUtils.getUI().msg("Pick a blueprint first");
                    return;
                }
                command = Command.LOAD;
                ready = true;
            }
        }, prev.pos("bl").add(UI.scale(0, 5)));

        Widget loadBtn = prev;
        prev = add(new Button(UI.scale(110), "Save as...") {
            @Override
            public void click() {
                super.click();
                promptSaveName();
            }
        }, new Coord(loadBtn.c.x + loadBtn.sz.x + UI.scale(10), loadBtn.c.y));

        prev = add(new Button(UI.scale(110), "Clear ghosts") {
            @Override
            public void click() {
                super.click();
                command = Command.CLEAR;
                ready = true;
            }
        }, loadBtn.pos("bl").add(UI.scale(0, 10)));

        Widget clearBtn = prev;
        prev = add(new Button(UI.scale(110), "Build all") {
            @Override
            public void click() {
                super.click();
                command = Command.BUILD;
                ready = true;
            }
        }, new Coord(clearBtn.c.x + clearBtn.sz.x + UI.scale(10), clearBtn.c.y));

        pack();
    }

    private void promptSaveName() {
        Window dialog = new Window(UI.scale(new Coord(300, 100)), "Save blueprint as") {
            {
                Widget p;
                p = add(new Label("Name:"), new Coord(UI.scale(10), UI.scale(10)));
                TextEntry nameEntry;
                int next = blueprintNames.size() + 1;
                p = add(nameEntry = new TextEntry(UI.scale(200), "Blueprint " + next),
                        p.pos("ur").adds(UI.scale(10), 0));

                add(new haven.Button(UI.scale(80), "Save") {
                    public void click() {
                        String name = nameEntry.text().trim();
                        if (name.isEmpty()) return;
                        saveName = name;
                        command = Command.SAVE;
                        ready = true;
                        parent.reqdestroy();
                    }
                }, new Coord(UI.scale(60), UI.scale(60)));

                add(new haven.Button(UI.scale(80), "Cancel") {
                    public void click() {
                        parent.reqdestroy();
                    }
                }, new Coord(UI.scale(160), UI.scale(60)));

                pack();
            }
            @Override
            public void wdgmsg(String msg, Object... args) {
                if (msg.equals("close")) reqdestroy();
                else super.wdgmsg(msg, args);
            }
        };
        ui.root.add(dialog, new Coord(ui.root.sz.x / 2 - dialog.sz.x / 2, ui.root.sz.y / 2 - dialog.sz.y / 2));
    }

    private void loadBlueprintNames() {
        blueprintNames.clear();
        try {
            File f = new File("world_blueprints.json");
            if (!f.exists()) return;
            String content = new String(Files.readAllBytes(f.toPath()));
            JSONObject root = new JSONObject(content);
            JSONArray bps = root.optJSONArray("blueprints");
            if (bps == null) return;
            for (int i = 0; i < bps.length(); i++) {
                blueprintNames.add(bps.getJSONObject(i).getString("name"));
            }
        } catch (Exception e) {
            // Silently ignore — fresh start
        }
    }

    /** Called by the bot after a successful save/load so the dropdown reflects file. */
    public void refreshBlueprintNames() {
        loadBlueprintNames();
    }

    public void setSelectedType(String name) {
        selectedType = name;
        if (statusLabel != null) statusLabel.settext("Selected: " + (name != null ? name : "(none)"));
    }

    public void setRotation(int rot) {
        displayRotation = rot & 3;
        if (rotationLabel != null) rotationLabel.settext("Rotation: " + (displayRotation * 90) + "°  (wheel to rotate)");
    }

    public void setGhostCount(int n) {
        this.ghostCount = n;
    }

    public String getSelectedType() { return selectedType; }
    public int getDisplayRotation() { return displayRotation; }

    public Command getCommand() { return command; }
    public String getLoadTarget() { return loadTarget; }
    public String getSaveName() { return saveName; }

    public void resetCommand() {
        command = Command.NONE;
        ready = false;
        saveName = null;
        // keep loadTarget so dropdown selection persists
    }

    @Override
    public boolean check() {
        return ready;
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if (msg.equals("close")) {
            command = Command.EXIT;
            ready = true;
            hide();
        }
        super.wdgmsg(msg, args);
    }

    private class BuildingPalette extends SListBox<BuildCatalog.BuildingDef, Widget> {
        private final List<BuildCatalog.BuildingDef> items;

        BuildingPalette(Coord sz) {
            super(sz, UI.scale(24));
            this.items = new ArrayList<>(BuildCatalog.all());
        }

        @Override
        protected List<BuildCatalog.BuildingDef> items() { return items; }

        @Override
        protected Widget makeitem(BuildCatalog.BuildingDef def, int idx, Coord sz) {
            return new ItemWidget<BuildCatalog.BuildingDef>(this, sz, def) {
                {
                    String label = def.displayName + " (" + def.tileFootprint.x + "x" + def.tileFootprint.y + ")";
                    add(new Label(label), new Coord(UI.scale(4), (sz.y - UI.scale(15)) / 2));
                }

                @Override
                public boolean mousedown(MouseDownEvent ev) {
                    boolean r = super.mousedown(ev);
                    if (ev.b == 1) {
                        selectedType = def.name;
                        command = Command.SELECT_TYPE;
                        ready = true;
                    }
                    return r;
                }
            };
        }

        @Override
        public void draw(GOut g) {
            g.chcolor(new java.awt.Color(30, 40, 40, 160));
            g.frect(Coord.z, g.sz());
            super.draw(g);
        }
    }
}
