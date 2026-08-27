package nurgling.widgets.options;

import haven.Button;
import haven.Coord;
import haven.GOut;
import haven.Text;
import haven.UI;
import haven.Widget;
import nurgling.NCore;
import nurgling.db.service.DbSizeService;
import nurgling.i18n.L10n;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The "how much disk is this costing me" section of the database settings panel.
 *
 * <p>A total on its own answers half the question. The bars answer the other half - which of the
 * things the client now stores is responsible - which is the part that tells a player whether to
 * prune their shared map or leave it alone.
 *
 * <p>Everything but the bars shares one line with the Refresh button. That is not tidiness: this
 * section lives on whatever height the settings above it leave over, which is around a hundred
 * pixels, and every line spent on a caption is a table the player cannot see.
 *
 * <p>Measurements arrive on database worker threads and are handed over through one volatile field;
 * every widget here is only ever touched from {@link #tick}, the same arrangement the Villagers
 * window uses.
 */
public class DbSizeView extends Widget {
    /**
     * Below this a table is not worth a line of its own. Nineteen tables where one holds the map
     * and the rest hold a few hundred kilobytes between them is a list you have to read to learn
     * nothing - so they arrive as a single row, and the ones that can actually fill a disk stand
     * out instead of being buried among them.
     */
    private static final long SMALL_TABLE_BYTES = 1024L * 1024L;

    private static final Color HEADER = new Color(220, 200, 150);
    private static final Color TOTAL = new Color(120, 230, 150);
    private static final Color LABEL = new Color(200, 200, 200);
    private static final Color DIM = new Color(140, 140, 140);
    private static final Color PROBLEM = new Color(235, 165, 90);
    private static final Color BAR = new Color(90, 150, 210);
    private static final Color BAR_REST = new Color(105, 105, 105);
    private static final Color BAR_BG = new Color(45, 45, 45);

    private final int nameW = UI.scale(120);
    private final int sizeW = UI.scale(64);
    /** Never shorter than the text it has to hold: the font is the player's to choose. */
    private final int rowH = Math.max(UI.scale(14), Text.std.height());
    private final int barH = UI.scale(8);
    private final int gap = UI.scale(6);

    private final Button refresh;
    private final Text title;
    private final int barTop;

    /* Handed over from a database thread; consumed and cleared in tick(). */
    private volatile DbSizeService.DbSize incoming = null;
    private volatile String incomingProblem = null;

    private DbSizeService.DbSize shown = null;
    private String problem = null;
    private boolean measuring = false;
    /** When the shown measurement was taken, so re-opening the panel does not re-scan. */
    private long measuredAt = 0;

    private final List<Row> rows = new ArrayList<>();
    private Text totalText = null;
    private Text detailText = null;

    private static class Row {
        final Text name;
        final Text size;
        final double share;
        final boolean rest;

        Row(Text name, Text size, double share, boolean rest) {
            this.name = name;
            this.size = size;
            this.share = share;
            this.rest = rest;
        }
    }

    public DbSizeView(int width) {
        super(new Coord(width, 0));
        title = Text.render(L10n.get("database.size.header"), HEADER);
        refresh = add(new Button(UI.scale(80), L10n.get("database.size.refresh")) {
            public void click() {
                super.click();
                /* The press is the consent for the expensive one: on SQLite the breakdown has to
                 * read the whole file, which is why opening the panel does not do it by itself. */
                measure(true);
            }
        }, new Coord(width - UI.scale(80), 0));
        refresh.tooltip = Text.render(L10n.get("database.size.refresh_tip")).tex();

        barTop = Math.max(refresh.sz.y, Text.std.height()) + UI.scale(6);
        resize(new Coord(width, barTop + rowH));
    }

    /** Forget the last measurement, so the next time the panel is shown it asks again. */
    public void invalidate() {
        shown = null;
        rows.clear();
        totalText = null;
        detailText = null;
        problem = null;
        measuredAt = 0;
    }

    /**
     * Measure if the last figure is stale, and do nothing at all when it is fresh.
     *
     * <p>Called every time the settings panel is shown, and switching between settings pages shows
     * it again - a database round trip per click is not what anyone asked for.
     */
    public void refreshIfStale() {
        if (shown != null && System.currentTimeMillis() - measuredAt < 60_000)
            return;
        /* Never the deep scan, however recently the button was pressed: opening a settings page
         * must not be what silently reads a gigabyte off the disk. */
        measure(false);
    }

    private void measure(boolean deepScan) {
        if (measuring)
            return;
        if (NCore.databaseManager == null || !NCore.databaseManager.isReady()
            || NCore.databaseManager.getDbSizeService() == null) {
            shown = null;
            rows.clear();
            totalText = null;
            detailText = null;
            problem = L10n.get("database.size.notconnected");
            return;
        }

        measuring = true;
        problem = null;
        try {
            NCore.databaseManager.getDbSizeService().measureAsync(deepScan)
                .thenAccept(size -> incoming = size)
                .exceptionally(e -> {
                    incomingProblem = L10n.get("database.size.failed", rootMessage(e));
                    return null;
                });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            /* The manager is being torn down and rebuilt - saving these settings does exactly that.
             * Say so here rather than let it out onto the UI thread, which is what draws the panel. */
            measuring = false;
            problem = L10n.get("database.size.notconnected");
        }
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);

        DbSizeService.DbSize size = incoming;
        if (size != null) {
            incoming = null;
            measuring = false;
            shown = size;
            measuredAt = System.currentTimeMillis();
            rebuild(size);
        }

        String p = incomingProblem;
        if (p != null) {
            incomingProblem = null;
            measuring = false;
            problem = p;
        }
    }

    /** Turn a measurement into the lines and bars actually drawn, once, off the drawing path. */
    private void rebuild(DbSizeService.DbSize size) {
        rows.clear();

        totalText = Text.render(DbSizeService.humanBytes(size.total), TOTAL);

        String detail;
        if (size.hasBreakdown()) {
            detail = L10n.get("database.size.detail", size.backend, size.tables.size());
        } else if ("unsupported".equals(size.breakdownNote)) {
            detail = L10n.get("database.size.nobreakdown", size.backend);
        } else {
            detail = L10n.get("database.size.pressrefresh", size.backend);
        }
        /* Trimmed to whatever is left of the header line, so a long back-end name cannot slide
         * under the Refresh button. */
        int detailRoom = refresh.c.x - gap
                       - (title.sz().x + gap) - (totalText.sz().x + gap);
        detailText = Text.render(clip(detail, detailRoom), DIM);

        if (size.hasBreakdown() && size.total > 0) {
            long tableSum = 0;
            long smallSum = 0;
            List<DbSizeService.Entry> small = new ArrayList<>();

            for (DbSizeService.Entry e : size.tables) {
                tableSum += e.bytes;
                if (e.bytes < SMALL_TABLE_BYTES) {
                    small.add(e);
                    smallSum += e.bytes;
                } else {
                    rows.add(bar(e.name, e.bytes, size.total, LABEL, false));
                }
            }

            /* One of them is not a fold - it is the same row with its name taken away, which is
             * strictly worse. Two or more is where the summary starts earning its line. */
            if (small.size() == 1) {
                DbSizeService.Entry e = small.get(0);
                rows.add(bar(e.name, e.bytes, size.total, LABEL, false));
            } else if (!small.isEmpty()) {
                rows.add(bar(L10n.get("database.size.small", small.size()),
                             smallSum, size.total, DIM, true));
            }

            /* Whatever the tables do not account for: on PostgreSQL the system catalogues and
             * pages a delete freed but never returned to the filesystem. Shown rather than
             * dropped, so the bars add up to the total above them. */
            long rest = size.total - tableSum;
            if (rest > 0 && (double) rest / size.total > 0.005) {
                rows.add(bar(L10n.get("database.size.rest_none"), rest, size.total, DIM, true));
            }
        }

        /* Grow to what there is to show. The panel's scroll content re-packs itself around this,
         * which is what puts the new rows within reach of the scrollbar. */
        resize(new Coord(sz.x, barTop + Math.max(1, rows.size()) * rowH));
    }

    private Row bar(String name, long bytes, long total, Color color, boolean summary) {
        return new Row(Text.render(clip(name, nameW), color),
                       Text.render(DbSizeService.humanBytes(bytes), color),
                       (double) bytes / total, summary);
    }

    @Override
    public void draw(GOut g) {
        super.draw(g);

        /* Caption, figure and status all on the button's line. A status - measuring, or a failure -
         * takes the place of the figure rather than pushing the bars down a row. */
        int ty = (refresh.sz.y - title.sz().y) / 2;
        int x = 0;
        g.image(title.tex(), new Coord(x, ty));
        x += title.sz().x + gap;

        if (problem != null) {
            g.chcolor(PROBLEM);
            g.text(problem, new Coord(x, ty));
            g.chcolor();
        } else if (measuring && shown == null) {
            g.chcolor(DIM);
            g.text(L10n.get("database.size.measuring"), new Coord(x, ty));
            g.chcolor();
        } else if (totalText != null) {
            g.image(totalText.tex(), new Coord(x, ty));
            x += totalText.sz().x + gap;
            if (detailText != null)
                g.image(detailText.tex(), new Coord(x, ty));
        }

        int y = barTop;
        int barX = nameW + UI.scale(4);
        int barW = sz.x - barX - sizeW - UI.scale(6);
        for (Row row : rows) {
            g.image(row.name.tex(), new Coord(0, y + (rowH - row.name.sz().y) / 2));

            int barY = y + (rowH - barH) / 2;
            g.chcolor(BAR_BG);
            g.frect(new Coord(barX, barY), new Coord(barW, barH));
            /* At least a pixel: a table that is a rounding error of the total still deserves to
             * look like something rather than like an empty row. */
            int w = Math.max(UI.scale(1), (int) Math.round(barW * Math.min(1.0, row.share)));
            g.chcolor(row.rest ? BAR_REST : BAR);
            g.frect(new Coord(barX, barY), new Coord(w, barH));
            g.chcolor();

            g.image(row.size.tex(),
                new Coord(sz.x - row.size.sz().x, y + (rowH - row.size.sz().y) / 2));
            y += rowH;
        }
    }

    /** Trim to a column, so a long name cannot run under whatever sits beside it. */
    private static String clip(String text, int maxWidth) {
        if (text == null)
            return "";
        if (Text.std.strsize(text).x <= maxWidth)
            return text;
        String s = text;
        while (s.length() > 1 && Text.std.strsize(s + "...").x > maxWidth)
            s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c)
            c = c.getCause();
        String m = c.getMessage();
        return (m == null || m.isEmpty()) ? String.valueOf(c) : m;
    }
}
