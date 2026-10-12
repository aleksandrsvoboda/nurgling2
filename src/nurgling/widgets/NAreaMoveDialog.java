package nurgling.widgets;

import haven.*;
import nurgling.NStyle;
import nurgling.NUtils;
import nurgling.areas.AreaPaths;
import nurgling.areas.NArea;

import java.awt.Color;
import java.util.*;
import java.util.List;

import static nurgling.i18n.L10n.get;

/**
 * "Move to...": the whole folder tree to pick a destination from, for when dragging is awkward -
 * a far-away folder, or search results, which have no folder rows to drop on.
 */
public class NAreaMoveDialog extends Window
{
    private final NAreasWidget areas;
    private final List<NArea> movedAreas;
    private final List<String> movedFolders;
    /** Where everything moved sits now, when it all sits in one folder. */
    private final String source;
    private final List<String> rows = new ArrayList<>();
    private final FolderList list;
    private final TextEntry name;
    private final Button move;
    private String clickedRow;
    private double clickedAt;

    public NAreaMoveDialog(NAreasWidget areas, List<NArea> movedAreas, List<String> movedFolders)
    {
        super(UI.scale(new Coord(240, 200)), title(movedAreas, movedFolders));
        this.areas = areas;
        this.movedAreas = movedAreas;
        this.movedFolders = movedFolders;
        Set<String> sources = new HashSet<>();
        for (NArea area : movedAreas)
            sources.add(area.path);
        for (String folder : movedFolders)
            sources.add(AreaPaths.parent(folder));
        source = (sources.size() == 1) ? sources.iterator().next() : null;

        list = add(new FolderList(UI.scale(new Coord(240, 200))), Coord.z);
        name = add(new TextEntry(UI.scale(150), "") {
            @Override
            public void activate(String text) {
                newFolder();
            }
        }, list.pos("bl").adds(0, 8));
        name.settip(get("area.move.new_folder_tip"));
        Button create = add(new Button(UI.scale(85), get("area.move.new_folder")) {
            @Override
            public void click() {
                newFolder();
            }
        }, name.pos("ur").adds(5, -6));
        move = add(new Button(UI.scale(80), get("area.move.btn")) {
            @Override
            public void click() {
                doMove();
            }
        }, name.pos("bl").adds(0, 8));
        add(new Button(UI.scale(80), get("edit.cancel")) {
            @Override
            public void click() {
                close();
            }
        }, move.pos("ur").adds(5, 0));
        pack();
        refresh();
        if (source != null)
            list.change(source);
    }

    private static String title(List<NArea> areas, List<String> folders)
    {
        if (areas.size() + folders.size() == 1)
            return get("area.move.title", areas.isEmpty() ? AreaPaths.name(folders.get(0)) : areas.get(0).name);
        return get("area.move.title", get("area.move.count", areas.size() + folders.size()));
    }

    private boolean valid(String dest)
    {
        List<String> paths = new ArrayList<>();
        for (NArea area : movedAreas)
            paths.add(area.path);
        return AreaPaths.canDrop(paths, movedFolders, dest);
    }

    private void refresh()
    {
        List<String> paths = new ArrayList<>(areas.emptyFolders);
        Map<Integer, NArea> all = NUtils.getGameUI().map.glob.map.areas;
        synchronized (all) {
            for (NArea area : all.values())
                paths.add(area.path);
        }
        rows.clear();
        rows.add(AreaPaths.ROOT);
        rows.addAll(AreaPaths.allFolders(paths));
        list.reset();
    }

    /** Makes a folder inside the highlighted one and highlights it. */
    private void newFolder()
    {
        String n = AreaPaths.clean(name.text());
        if (n.isEmpty())
            return;
        String parent = (list.sel != null) ? list.sel : AreaPaths.ROOT;
        String folder = AreaPaths.join(parent, n);
        areas.emptyFolders.add(folder);
        name.settext("");
        refresh();
        list.change(folder);
        list.display();
    }

    private void doMove()
    {
        if (list.sel == null || !valid(list.sel))
            return;
        areas.moveInto(movedAreas, movedFolders, list.sel);
        close();
    }

    private void close()
    {
        areas.moveDialogClosed(this);
        reqdestroy();
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args)
    {
        if (msg.equals("close"))
            close();
        else
            super.wdgmsg(sender, msg, args);
    }

    @Override
    public void tick(double dt)
    {
        super.tick(dt);
        move.disable(list.sel == null || !valid(list.sel));
    }

    private class FolderList extends SListBox<String, Widget>
    {
        FolderList(Coord sz)
        {
            super(sz, UI.scale(18));
        }

        @Override
        protected List<String> items()
        {
            return rows;
        }

        @Override
        protected Widget makeitem(String folder, int idx, Coord sz)
        {
            return new Row(this, sz, folder);
        }

        @Override
        public void change(String folder)
        {
            /* A row things can't move to is shown but can't be picked. */
            if (folder == null || valid(folder))
                super.change(folder);
        }

        @Override
        public void draw(GOut g)
        {
            g.chcolor(30, 40, 40, 160);
            g.frect(Coord.z, g.sz());
            g.chcolor();
            super.draw(g);
        }
    }

    private class Row extends SListWidget.ItemWidget<String>
    {
        private final Text.Line text;
        private final boolean ok;

        Row(FolderList list, Coord sz, String folder)
        {
            super(list, sz, folder);
            String label = folder.isEmpty() ? get("area.move.root") : AreaPaths.name(folder);
            if (folder.equals(source))
                label += " " + get("area.move.here");
            int x = UI.scale(4) + UI.scale(12) * AreaPaths.depth(folder);
            text = Text.std.ellipsize(label, sz.x - x - UI.scale(24));
            ok = valid(folder);
        }

        @Override
        protected boolean clicked(MouseDownEvent ev)
        {
            /* A second click on the same row soon after is a double-click: move there. */
            double now = Utils.rtime();
            boolean twice = item.equals(clickedRow) && (now - clickedAt) < 0.4;
            clickedRow = item;
            clickedAt = now;
            list.change(item);
            if (twice)
                doMove();
            return true;
        }

        @Override
        public void draw(GOut g)
        {
            int x = UI.scale(4) + UI.scale(12) * AreaPaths.depth(item);
            Coord icon = UI.scale(16, 16);
            int y = (sz.y - icon.y) / 2;
            Color tint = ok ? Color.WHITE : new Color(255, 255, 255, 90);
            g.chcolor(tint);
            g.image(item.isEmpty() ? NAreasWidget.openfolderIcon : NAreasWidget.folderIcon, Coord.of(x, y), icon);
            g.image(text.tex(), Coord.of(x + icon.x + UI.scale(4), (sz.y - text.sz().y) / 2));
            g.chcolor();
        }

        @Override
        public void dispose()
        {
            text.dispose();
            super.dispose();
        }
    }
}
