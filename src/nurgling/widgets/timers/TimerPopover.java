package nurgling.widgets.timers;

import haven.*;
import nurgling.NGameUI;
import nurgling.i18n.L10n;
import nurgling.timers.Timer;
import nurgling.timers.TimerDurations;
import nurgling.timers.TimerPlacement;
import nurgling.timers.TimerStore;
import nurgling.widgets.cookbook.CookbookTheme;
import nurgling.widgets.cookbook.PillButton;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The small window for setting or changing one timer: preset chips (the first is the length last used for
 * this kind of resource), one field that reads {@code 3h20m}, {@code 3:20} or {@code 200m} with the ready
 * time previewed, and for pins and reminders a name, colour and repeat. Opens next to the click.
 */
public class TimerPopover extends Window {
    private static final int W = UI.scale(270);
    private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;
    private static final long[] RESOURCE_PRESETS = {HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR, DAY};
    private static final long[] PIN_PRESETS = {30 * MIN, HOUR, 3 * HOUR, 6 * HOUR, DAY};
    private static final long[] REMINDER_PRESETS = {15 * MIN, 30 * MIN, HOUR, 2 * HOUR, 4 * HOUR};

    private enum Repeat {OFF, SAME, DAILY}

    private final NGameUI gui;
    private final Timer.Kind kind;
    private final Timer existing;
    private final TimerPlacement.Spot spot;
    private final String resType;
    private final String fixedName;

    private TextEntry nameEntry;
    private TextEntry durationEntry;
    private Label preview;
    private CheckBox share;
    private String color;
    private Repeat repeat = Repeat.OFF;
    private final List<PillButton> repeatChips = new ArrayList<>();

    private TimerPopover(NGameUI gui, Timer.Kind kind, Timer existing, TimerPlacement.Spot spot, String resType, String name) {
        super(Coord.of(W, UI.scale(60)), title(kind, existing, name));
        this.gui = gui;
        this.kind = kind;
        this.existing = existing;
        this.spot = spot;
        this.resType = resType;
        this.fixedName = name;
        this.color = (existing != null && existing.icon != null) ? existing.icon : TimerIcons.PIN_COLORS[0];
        if(existing != null && existing.repeatMs > 0)
            repeat = (existing.repeatMs == DAY) ? Repeat.DAILY : Repeat.SAME;
        build();
    }

    private static String title(Timer.Kind kind, Timer existing, String name) {
        String n = (existing != null) ? TimerBanners.displayName(existing) : name;
        if(n == null || n.isEmpty())
            return L10n.get("timers.popover.title_new_" + kind.key());
        return L10n.get("timers.popover.title", n);
    }

    /** Shift+right-click on a resource marker: its timer, or a new one. */
    public static TimerPopover forResource(NGameUI gui, MapFile.SMarker marker, String displayName) {
        TimerPlacement.Spot spot = TimerPlacement.fromSegment(gui.mmap.file, marker.seg, marker.tc);
        if(spot == null)
            return null;
        Timer t = gui.timerStore.findResource(spot.gridId, spot.offset.x, spot.offset.y, marker.res.name);
        if(t != null)
            return edit(gui, t);
        return new TimerPopover(gui, Timer.Kind.RESOURCE, null, spot, marker.res.name, displayName);
    }

    public static TimerPopover newPin(NGameUI gui, TimerPlacement.Spot spot, String name) {
        return new TimerPopover(gui, Timer.Kind.PIN, null, spot, null, name);
    }

    public static TimerPopover edit(NGameUI gui, Timer t) {
        return new TimerPopover(gui, t.kind, t, null, t.resType, t.name);
    }

    private void build() {
        int y = 0;
        long now = System.currentTimeMillis();
        if(existing != null) {
            String status = existing.isReady(now)
                ? L10n.get("timers.ready_ago", TimerDurations.format(now - existing.readyAt()))
                : L10n.get("timers.popover.status", TimerDurations.format(existing.remaining(now)),
                    TimerDurations.formatClock(existing.readyAt(), L10n.get("timers.tomorrow")));
            Label st = add(new Label(status), Coord.of(0, y));
            st.setcolor(existing.isReady(now) ? TimerIcons.READY : CookbookTheme.accent);
            y += st.sz.y + UI.scale(6);
        }

        if(kind != Timer.Kind.RESOURCE) {
            add(new Label(L10n.get("timers.popover.name")), Coord.of(0, y + UI.scale(3)));
            nameEntry = add(new TextEntry(W - UI.scale(50), (fixedName == null) ? "" : fixedName), Coord.of(UI.scale(50), y));
            y += UI.scale(26);
        }

        // Preset chips; the first is what was used last time for this kind of timer.
        String key = TimerStore.durationKey(kind, resType);
        long last = gui.timerStore.lastDuration(key);
        List<Long> presets = new ArrayList<>();
        if(last > 0)
            presets.add(last);
        for(long p : presetsFor(kind)) {
            if(p != last)
                presets.add(p);
        }
        int x = 0;
        for(int i = 0; i < presets.size(); i++) {
            long ms = presets.get(i);
            String label = TimerDurations.formatShort(ms) + ((i == 0 && last > 0) ? " " + L10n.get("timers.popover.last") : "");
            PillButton chip = new PillButton(label, false, () -> {
                durationEntry.settext(TimerDurations.formatShort(ms));
                updatePreview();
            });
            if(x + chip.sz.x > W) {
                x = 0;
                y += chip.sz.y + UI.scale(4);
            }
            add(chip, Coord.of(x, y));
            x += chip.sz.x + UI.scale(4);
        }
        y += UI.scale(28);

        PillButton start = new PillButton(L10n.get(existing == null ? "timers.popover.start" : "timers.popover.save"), false, this::submit);
        String initial = (existing != null) ? "" : ((last > 0) ? TimerDurations.formatShort(last) : "");
        durationEntry = add(new TextEntry(W - start.sz.x - UI.scale(6), initial) {
            @Override
            public void activate(String text) {
                submit();
            }

            @Override
            protected void changed() {
                super.changed();
                updatePreview();
            }
        }, Coord.of(0, y + (start.sz.y - UI.scale(20)) / 2));
        add(start, Coord.of(W - start.sz.x, y));
        y += start.sz.y + UI.scale(3);
        preview = add(new Label(""), Coord.of(0, y));
        y += UI.scale(20);

        if(kind == Timer.Kind.PIN) {
            add(new Label(L10n.get("timers.popover.color")), Coord.of(0, y + UI.scale(2)));
            int sx = UI.scale(50);
            for(String c : TimerIcons.PIN_COLORS) {
                Swatch s = add(new Swatch(c), Coord.of(sx, y));
                sx += s.sz.x + UI.scale(5);
            }
            y += UI.scale(24);
        }

        if(kind != Timer.Kind.RESOURCE) {
            add(new Label(L10n.get("timers.popover.repeat")), Coord.of(0, y + UI.scale(4)));
            int rx = UI.scale(50);
            for(Repeat r : Repeat.values()) {
                PillButton chip = new PillButton(L10n.get("timers.repeat." + r.name().toLowerCase()), false, () -> setRepeat(r));
                repeatChips.add(chip);
                add(chip, Coord.of(rx, y));
                rx += chip.sz.x + UI.scale(4);
            }
            setRepeat(repeat);
            y += UI.scale(28);
        }

        boolean shared = (existing != null) ? existing.shared : (kind != Timer.Kind.REMINDER);
        share = add(new CheckBox(L10n.get("timers.popover.share")), Coord.of(0, y));
        share.a = shared;
        y += UI.scale(24);

        if(existing != null) {
            Timer t = existing;
            PillButton restart = new PillButton(L10n.get("timers.action.restart", TimerDurations.formatShort(t.durationMs)), false, () -> {
                gui.timerStore.restart(t.id, System.currentTimeMillis());
                close();
            });
            add(restart, Coord.of(0, y));
            PillButton remove = new PillButton(L10n.get(t.shared ? "timers.popover.remove_shared" : "timers.popover.remove"), false, () -> {
                gui.timerStore.remove(t.id);
                close();
            });
            add(remove, Coord.of(restart.sz.x + UI.scale(6), y));
            y += restart.sz.y + UI.scale(4);
        }
        resize(Coord.of(W, y));
        updatePreview();
    }

    private static long[] presetsFor(Timer.Kind kind) {
        switch(kind) {
            case RESOURCE: return RESOURCE_PRESETS;
            case PIN: return PIN_PRESETS;
            default: return REMINDER_PRESETS;
        }
    }

    private void setRepeat(Repeat r) {
        repeat = r;
        for(int i = 0; i < repeatChips.size(); i++)
            repeatChips.get(i).expanded = (Repeat.values()[i] == r);
    }

    private void updatePreview() {
        if(preview == null || durationEntry == null)
            return;
        String text = durationEntry.text().trim();
        if(text.isEmpty()) {
            preview.settext(existing != null ? L10n.get("timers.popover.keep") : L10n.get("timers.popover.formats"));
            preview.setcolor(CookbookTheme.muted);
            return;
        }
        long ms = TimerDurations.parse(text);
        if(ms < 0) {
            preview.settext(L10n.get("timers.popover.bad_duration"));
            preview.setcolor(CookbookTheme.warn);
        } else {
            long at = System.currentTimeMillis() + ms;
            preview.settext(L10n.get("timers.popover.ready_at", TimerDurations.formatClock(at, L10n.get("timers.tomorrow"))));
            preview.setcolor(CookbookTheme.muted);
        }
    }

    private void submit() {
        String text = durationEntry.text().trim();
        long ms = text.isEmpty() ? -1 : TimerDurations.parse(text);
        if(ms < 0 && (existing == null || !text.isEmpty())) {
            preview.settext(L10n.get("timers.popover.bad_duration"));
            preview.setcolor(CookbookTheme.warn);
            return;
        }
        long now = System.currentTimeMillis();
        String name = (nameEntry != null) ? nameEntry.text().trim() : fixedName;
        if(name == null || name.isEmpty())
            name = L10n.get("timers.banner.kind_" + kind.key());
        TimerStore store = gui.timerStore;
        long cycle = (ms > 0) ? ms : ((existing != null) ? existing.durationMs : 0);
        long repeatMs = (repeat == Repeat.DAILY) ? DAY : ((repeat == Repeat.SAME) ? cycle : 0);
        String icon = (kind == Timer.Kind.PIN) ? color : null;

        Timer t;
        if(existing != null) {
            t = existing.withDetails(name, icon, repeatMs, share.a);
            if(ms > 0)
                t = t.restarted(now, ms);
        } else {
            String id = (kind == Timer.Kind.RESOURCE)
                ? Timer.resourceId(store.genus(), spot.gridId, spot.offset.x, spot.offset.y, resType)
                : Timer.randomId();
            long gid = (spot != null) ? spot.gridId : 0;
            int ox = (spot != null) ? spot.offset.x : 0;
            int oy = (spot != null) ? spot.offset.y : 0;
            t = new Timer(id, kind, gid, ox, oy, resType, name, icon, now, ms, repeatMs, gui.chrid, share.a, 0, 0, null);
        }
        store.put(t);
        if(ms > 0)
            store.rememberDuration(TimerStore.durationKey(kind, resType), ms);
        close();
    }

    private void close() {
        ui.destroy(this);
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if(msg.equals("close"))
            close();
        else
            super.wdgmsg(msg, args);
    }

    @Override
    public boolean keydown(KeyDownEvent ev) {
        if(ev.code == java.awt.event.KeyEvent.VK_ESCAPE) {
            close();
            return true;
        }
        return super.keydown(ev);
    }

    /** One pin colour to pick. */
    private class Swatch extends Widget {
        private final String key;

        Swatch(String key) {
            super(UI.scale(18, 18));
            this.key = key;
        }

        @Override
        public void draw(GOut g) {
            Color c = TimerIcons.pinColor(key);
            CookbookTheme.fill(g, Coord.of(UI.scale(3), UI.scale(3)), sz.sub(UI.scale(6), UI.scale(6)), c);
            CookbookTheme.frame(g, Coord.z, sz, key.equals(color) ? CookbookTheme.fg : CookbookTheme.outline);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if(ev.b == 1) {
                color = key;
                return true;
            }
            return super.mousedown(ev);
        }
    }
}
