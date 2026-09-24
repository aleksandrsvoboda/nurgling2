package nurgling.widgets.nsettings;

import haven.*;
import haven.res.lib.itemtex.ItemTex;
import nurgling.NUtils;
import nurgling.cheese.CheeseBranch;
import nurgling.cheese.CheeseOrder;
import nurgling.cheese.CheeseOrdersManager;
import nurgling.cheese.ConveyorOrder;
import nurgling.cheese.ConveyorOrdersManager;
import nurgling.i18n.L10n;
import nurgling.tools.VSpec;
import org.json.JSONObject;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

/**
 * Orders of the Cheese Conveyor bot: continuous orders (trays every H hours) on top, one-time
 * orders below. One order per cheese; a one-time order for a continuous cheese becomes extra trays.
 */
public class CheeseConveyorPanel extends Panel {
    private static final int MODE_ONCE = 0;
    private static final int MODE_CONTINUOUS = 1;

    private final ConveyorOrdersManager manager = new ConveyorOrdersManager();
    private final int margin = UI.scale(10);
    private final List<String> cheeseTypes = CheeseBranch.allProducts();
    private final List<String> modes = Arrays.asList(L10n.get("conveyor.mode.once"), L10n.get("conveyor.mode.continuous"));
    private final Set<Integer> expanded = new HashSet<>();
    private final Map<String, TexI> icons = new HashMap<>();
    private final Set<String> missingIcons = new HashSet<>();

    private final Widget listPanel;
    private final Widget editorPanel;
    private final Scrollport listScroll;
    private final Widget listContent;
    private final Dropbox<String> cheeseDropdown;
    private final Dropbox<String> modeDropdown;
    private final TextEntry traysEntry;
    private final TextEntry everyEntry;
    private long fileStamp = -1;
    private double sinceCheck = 0;

    public CheeseConveyorPanel() {
        super("");
        int contentWidth = sz.x - margin * 2;
        int contentHeight = sz.y - UI.scale(40);
        int btnHeight = UI.scale(28);

        // -------- List -----------
        listPanel = add(new Widget(new Coord(contentWidth, contentHeight)), new Coord(margin, margin));
        listPanel.add(new Label(L10n.get("conveyor.title")), Coord.z);
        listScroll = listPanel.add(new Scrollport(new Coord(contentWidth, UI.scale(430))), new Coord(0, UI.scale(28)));
        listContent = new Widget(new Coord(contentWidth - UI.scale(20), UI.scale(50))) {
            @Override
            public void pack() {
                resize(contentsz());
            }
        };
        listScroll.cont.add(listContent, Coord.z);
        int bottomY = contentHeight - btnHeight - UI.scale(8);
        listPanel.add(new Button(UI.scale(120), L10n.get("conveyor.add_order"), this::showEditor), new Coord(0, bottomY));
        listPanel.add(new Button(UI.scale(260), L10n.get("conveyor.import"), this::importOldOrders), new Coord(UI.scale(130), bottomY));

        // -------- Editor -----------
        editorPanel = add(new Widget(new Coord(contentWidth, contentHeight)), new Coord(margin, margin));
        int y = 0;
        editorPanel.add(new Label(L10n.get("conveyor.add_title")), new Coord(0, y));
        y += UI.scale(26);
        editorPanel.add(new Label(L10n.get("conveyor.cheese")), new Coord(0, y + UI.scale(2)));
        cheeseDropdown = editorPanel.add(dropdown(cheeseTypes, UI.scale(200)), new Coord(UI.scale(120), y));
        y += UI.scale(32);
        editorPanel.add(new Label(L10n.get("conveyor.mode")), new Coord(0, y + UI.scale(2)));
        modeDropdown = editorPanel.add(dropdown(modes, UI.scale(200)), new Coord(UI.scale(120), y));
        y += UI.scale(32);
        editorPanel.add(new Label(L10n.get("conveyor.trays")), new Coord(0, y + UI.scale(4)));
        traysEntry = editorPanel.add(new TextEntry(UI.scale(60), "1"), new Coord(UI.scale(120), y));
        y += UI.scale(32);
        editorPanel.add(new Label(L10n.get("conveyor.every")), new Coord(0, y + UI.scale(4)));
        everyEntry = editorPanel.add(new TextEntry(UI.scale(60), "24"), new Coord(UI.scale(120), y));
        y += UI.scale(36);
        editorPanel.add(new Label(L10n.get("conveyor.help.continuous"), contentWidth), new Coord(0, y));
        y += UI.scale(40);
        editorPanel.add(new Label(L10n.get("conveyor.help.once"), contentWidth), new Coord(0, y));

        int btnW = UI.scale(120);
        editorPanel.add(new Button(btnW, L10n.get("common.save"), this::saveOrder),
                new Coord((contentWidth - btnW * 2 - UI.scale(20)) / 2, bottomY));
        editorPanel.add(new Button(btnW, L10n.get("common.cancel"), this::showList),
                new Coord((contentWidth - btnW * 2 - UI.scale(20)) / 2 + btnW + UI.scale(20), bottomY));

        showList();
    }

    private static Dropbox<String> dropdown(List<String> items, int w) {
        Dropbox<String> d = new Dropbox<String>(w, Math.min(16, items.size()), UI.scale(16)) {
            @Override
            protected String listitem(int i) {
                return items.get(i);
            }

            @Override
            protected int listitems() {
                return items.size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }
        };
        d.sel = items.get(0);
        return d;
    }

    private void showList() {
        editorPanel.hide();
        listPanel.show();
        reload();
    }

    private void showEditor() {
        listPanel.hide();
        editorPanel.show();
        cheeseDropdown.sel = cheeseTypes.get(0);
        modeDropdown.sel = modes.get(MODE_CONTINUOUS);
        traysEntry.settext("1");
        everyEntry.settext("24");
    }

    private void saveOrder() {
        String cheese = cheeseDropdown.sel != null ? cheeseDropdown.sel : cheeseTypes.get(0);
        boolean continuous = modes.indexOf(modeDropdown.sel) == MODE_CONTINUOUS;
        double trays = parse(traysEntry.text());
        double every = parse(everyEntry.text());
        if (trays <= 0 || (!continuous && trays != Math.floor(trays))) {
            NUtils.getGameUI().msg(L10n.get("conveyor.invalid_trays"));
            return;
        }
        if (continuous && every <= 0) {
            NUtils.getGameUI().msg(L10n.get("conveyor.invalid_every"));
            return;
        }

        manager.load();
        ConveyorOrder order = manager.getOrCreate(cheese);
        if (order == null) {
            NUtils.getGameUI().msg(L10n.get("cheese.no_recipe") + " " + cheese);
            return;
        }
        if (continuous) {
            order.makeContinuous(trays, every);
            NUtils.getGameUI().msg(L10n.get("conveyor.saved_continuous", cheese, fmt(trays), fmt(every)));
        } else {
            order.addTrays((int) trays);
            NUtils.getGameUI().msg(L10n.get(order.isContinuous() ? "conveyor.saved_extra" : "conveyor.saved_once", cheese, String.valueOf((int) trays)));
        }
        manager.save();
        showList();
    }

    /** Copy the Cheese Production Bot's orders as one-time orders. The old orders are left as they are. */
    private void importOldOrders() {
        manager.load();
        int imported = 0;
        for (CheeseOrder old : new CheeseOrdersManager().getOrders().values()) {
            if (manager.byCheese(old.getCheeseType()) != null || CheeseBranch.getChainToProduct(old.getCheeseType()) == null)
                continue;
            int id = manager.getOrders().keySet().stream().max(Integer::compareTo).orElse(0) + 1;
            JSONObject json = old.toJson();
            json.put("id", id);
            manager.getOrders().put(id, new ConveyorOrder(json));
            imported++;
        }
        manager.save();
        NUtils.getGameUI().msg(L10n.get("conveyor.imported", String.valueOf(imported)));
        reload();
    }

    private void reload() {
        manager.load();
        fileStamp = new File(manager.getPath()).lastModified();
        rebuild();
    }

    @Override
    public void show() {
        super.show();
        reload();
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        // The bot updates the file while it runs; pick that up while the list is visible.
        sinceCheck += dt;
        if (sinceCheck >= 2 && visible() && listPanel.visible()) {
            sinceCheck = 0;
            if (new File(manager.getPath()).lastModified() != fileStamp)
                reload();
        }
    }

    private void rebuild() {
        for (Widget child : new ArrayList<>(listContent.children()))
            child.destroy();

        List<ConveyorOrder> orders = new ArrayList<>(manager.getOrders().values());
        orders.sort(Comparator.comparingInt(ConveyorOrder::getId));
        int w = listContent.sz.x;
        int y = 0;
        for (boolean continuous : new boolean[]{true, false}) {
            listContent.add(new Label(L10n.get(continuous ? "conveyor.section.continuous" : "conveyor.section.once")), new Coord(0, y));
            y += UI.scale(20);
            boolean any = false;
            for (ConveyorOrder order : orders) {
                if (order.isContinuous() != continuous)
                    continue;
                Widget row = orderRow(order, w);
                listContent.add(row, new Coord(0, y));
                y += row.sz.y + UI.scale(2);
                any = true;
            }
            if (!any) {
                listContent.add(new Label(L10n.get("conveyor.section.empty")), new Coord(UI.scale(10), y));
                y += UI.scale(20);
            }
            y += UI.scale(10);
        }
        listContent.pack();
        listScroll.cont.update();
    }

    private Widget orderRow(ConveyorOrder order, int w) {
        boolean open = expanded.contains(order.getId());
        List<CheeseOrder.StepStatus> steps = order.getStatus();
        int baseH = UI.scale(44);
        int stepH = UI.scale(20);
        int h = baseH + (open ? UI.scale(4) + steps.size() * stepH : 0);
        Widget row = new Widget(new Coord(w, h));

        row.add(new Widget(new Coord(w, baseH)) {
            @Override
            public void draw(GOut g) {
                g.chcolor(255, 255, 255, 8);
                g.frect(Coord.z, sz);
                g.chcolor();
            }
        }, Coord.z);

        row.add(new Button(UI.scale(26), open ? "▼" : "▶", () -> {
            if (!expanded.remove(order.getId()))
                expanded.add(order.getId());
            rebuild();
        }), new Coord(UI.scale(4), UI.scale(4)));

        TexI icon = icon(order.getCheeseType());
        if (icon != null) {
            row.add(new Widget(UI.scale(20, 20)) {
                @Override
                public void draw(GOut g) {
                    g.image(icon, Coord.z, sz);
                }
            }, new Coord(UI.scale(36), UI.scale(6)));
        }
        row.add(new Label(order.getCheeseType()), new Coord(UI.scale(62), UI.scale(6)));

        CheeseOrder.StepStatus start = order.startStep();
        String info;
        if (order.isContinuous()) {
            row.add(new Label(L10n.get("conveyor.rate", fmt(order.getTrays()), fmt(order.getEveryHours()))), new Coord(UI.scale(230), UI.scale(6)));
            info = L10n.get("conveyor.status.continuous", String.valueOf(order.inProgress()), String.valueOf(order.plannedWip()),
                    String.valueOf(start.left));
            if (order.extraLeft() > 0)
                info += "  " + L10n.get("conveyor.status.extra", String.valueOf(order.extraLeft()));
            if (order.isPaused())
                info += "  " + L10n.get("conveyor.status.paused");
            row.add(new Button(UI.scale(70), L10n.get(order.isPaused() ? "conveyor.resume" : "conveyor.pause"), () -> {
                manager.load();
                ConveyorOrder fresh = manager.getOrders().get(order.getId());
                if (fresh != null) {
                    fresh.setPaused(!fresh.isPaused());
                    manager.save();
                }
                reload();
            }), new Coord(w - UI.scale(150), UI.scale(4)));
        } else {
            row.add(new Label("x" + order.getCount()), new Coord(UI.scale(230), UI.scale(6)));
            info = L10n.get("conveyor.status.once", String.valueOf(order.inProgress()), String.valueOf(start.left));
        }
        row.add(new Label(info), new Coord(UI.scale(62), UI.scale(24)));

        row.add(new Button(UI.scale(70), L10n.get("common.delete"), () -> {
            manager.load();
            manager.delete(order.getId());
            manager.save();
            expanded.remove(order.getId());
            reload();
        }), new Coord(w - UI.scale(75), UI.scale(4)));

        if (open) {
            int y = baseH + UI.scale(4);
            for (CheeseOrder.StepStatus step : steps) {
                row.add(new Label(step.left == 0 ? "✓" : "○"), new Coord(UI.scale(40), y));
                row.add(new Label(step.name), new Coord(UI.scale(62), y));
                row.add(new Label(step.place), new Coord(UI.scale(230), y));
                row.add(new Label(L10n.get("conveyor.step_left", String.valueOf(step.left))), new Coord(UI.scale(310), y));
                y += stepH;
            }
        }
        return row;
    }

    /** Cheese icon from the VSpec cheese category, loaded once per type. */
    private TexI icon(String cheeseType) {
        if (icons.containsKey(cheeseType))
            return icons.get(cheeseType);
        if (missingIcons.contains(cheeseType) || !VSpec.categories.containsKey("Cheese"))
            return null;
        for (JSONObject obj : VSpec.categories.get("Cheese")) {
            if (!obj.getString("name").equals(cheeseType))
                continue;
            try {
                BufferedImage img = ItemTex.create(obj);
                if (img != null) {
                    TexI tex = new TexI(img);
                    icons.put(cheeseType, tex);
                    return tex;
                }
            } catch (RuntimeException e) {
                // resource not available: show the row without an icon
            }
            break;
        }
        missingIcons.add(cheeseType);
        return null;
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
