package nurgling.widgets.timers;

import haven.*;
import nurgling.NGameUI;
import nurgling.NStyle;
import nurgling.i18n.L10n;
import nurgling.timers.Timer;
import nurgling.timers.TimerDurations;
import nurgling.timers.TimerPlacement;
import nurgling.timers.TimerStore;
import nurgling.tools.GridLocator;
import nurgling.widgets.cookbook.CookbookTheme;
import nurgling.widgets.cookbook.PillButton;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Timers &amp; notifications: every timer of the world, ready ones first, with a one-line box for quick
 * reminders. Opened from the minimap's timers button or a banner.
 */
public class TimersPanel extends Window {
    public static final KeyBinding kb_quickadd = KeyBinding.get("timers_quickadd", KeyMatch.nil);

    private static final int W = UI.scale(380);
    private static final int H = UI.scale(430);
    private static final int PAD = UI.scale(8);
    private static final long[] REMINDER_CHIPS = {15 * 60_000L, 30 * 60_000L, 60 * 60_000L, 120 * 60_000L};

    private enum Filter {ALL, MINE, VILLAGE}

    private final TextEntry quick;
    private final Label hint;
    private final PillButton fMine, fVillage, fAll;
    private Filter filter = Filter.ALL;

    public TimersPanel() {
        super(Coord.of(W, H), L10n.get("timers.panel.title"));
        int y = 0;
        PillButton here = new PillButton(L10n.get("timers.panel.here"), false, this::pinHere);
        PillButton add = new PillButton(L10n.get("timers.panel.add_reminder"), false, this::submitQuick);
        int qw = W - here.sz.x - add.sz.x - UI.scale(10);
        quick = add(new TextEntry(qw, "") {
            @Override
            public void activate(String text) {
                submitQuick();
            }

            @Override
            protected void changed() {
                super.changed();
                if(hint != null) {
                    hint.settext(L10n.get("timers.panel.quick_hint"));
                    hint.setcolor(CookbookTheme.muted);
                }
            }
        }, Coord.of(0, y + (add.sz.y - UI.scale(20)) / 2));
        add(add, Coord.of(qw + UI.scale(5), y));
        add(here, Coord.of(qw + add.sz.x + UI.scale(10), y));
        y += add.sz.y + UI.scale(4);

        int x = 0;
        for(long ms : REMINDER_CHIPS) {
            PillButton chip = new PillButton(TimerDurations.formatShort(ms), false, () -> chip(ms));
            add(chip, Coord.of(x, y));
            x += chip.sz.x + UI.scale(4);
        }
        hint = add(new Label(L10n.get("timers.panel.quick_hint")), Coord.of(x + UI.scale(4), y + UI.scale(4)));
        hint.setcolor(CookbookTheme.muted);
        y += UI.scale(28);

        fAll = new PillButton(L10n.get("timers.filter.all"), false, () -> setFilter(Filter.ALL));
        fMine = new PillButton(L10n.get("timers.filter.mine"), false, () -> setFilter(Filter.MINE));
        fVillage = new PillButton(L10n.get("timers.filter.village"), false, () -> setFilter(Filter.VILLAGE));
        add(fAll, Coord.of(0, y));
        add(fMine, Coord.of(fAll.sz.x + UI.scale(4), y));
        add(fVillage, Coord.of(fAll.sz.x + fMine.sz.x + UI.scale(8), y));
        setFilter(Filter.ALL);
        y += fAll.sz.y + UI.scale(6);

        add(new TimerList(Coord.of(W, H - y)), Coord.of(0, y));
        hide();
    }

    private NGameUI gui() {
        return (NGameUI) parent;
    }

    private void setFilter(Filter f) {
        filter = f;
        fAll.expanded = (f == Filter.ALL);
        fMine.expanded = (f == Filter.MINE);
        fVillage.expanded = (f == Filter.VILLAGE);
    }

    /** Open with the reminder box focused, for the quick-add key. */
    public void showQuickAdd() {
        show();
        raise();
        parent.setfocus(this);
        setfocus(quick);
    }

    private void chip(long ms) {
        TimerDurations.Leading cur = TimerDurations.parseLeading(quick.text());
        String rest = (cur == null) ? quick.text().trim() : cur.rest;
        quick.settext(TimerDurations.formatShort(ms) + (rest.isEmpty() ? " " : " " + rest));
        setfocus(quick);
    }

    /** "1h30m check the smelters" → a private reminder. */
    private void submitQuick() {
        TimerDurations.Leading l = TimerDurations.parseLeading(quick.text());
        if(l == null) {
            hint.settext(L10n.get("timers.panel.quick_error"));
            hint.setcolor(CookbookTheme.warn);
            return;
        }
        NGameUI gui = gui();
        String name = l.rest.isEmpty() ? L10n.get("timers.banner.kind_reminder") : l.rest;
        long now = System.currentTimeMillis();
        gui.timerStore.put(new Timer(Timer.randomId(), Timer.Kind.REMINDER, 0, 0, 0, null, name, null,
            now, l.ms, 0, gui.chrid, false, 0, 0, null));
        gui.timerStore.rememberDuration(Timer.Kind.REMINDER.key(), l.ms);
        quick.settext("");
        hint.settext(L10n.get("timers.panel.quick_added", TimerDurations.formatClock(now + l.ms, L10n.get("timers.tomorrow"))));
    }

    /** A pin where the character stands. */
    private void pinHere() {
        NGameUI gui = gui();
        TimerPlacement.Spot spot = TimerPlacement.fromPlayer(gui);
        if(spot == null) {
            gui.msg(L10n.get("timers.here_unavailable"));
            return;
        }
        gui.showTimerPopover(TimerPopover.newPin(gui, spot, ""));
    }

    @Override
    public void show() {
        super.show();
        raise();
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if(msg.equals("close"))
            hide();
        else
            super.wdgmsg(msg, args);
    }

    @Override
    public boolean keydown(KeyDownEvent ev) {
        if(ev.code == java.awt.event.KeyEvent.VK_ESCAPE) {
            hide();
            return true;
        }
        return super.keydown(ev);
    }

    /** The list: a Ready section, then Coming up, drawn directly with per-row buttons. */
    private class TimerList extends Widget {
        private final int RH = UI.scale(38);
        private final int HH = UI.scale(20);
        private final int B = UI.scale(20);
        private int scroll = 0;
        private int contentH = 0;
        private final List<Hit> hits = new ArrayList<>();
        private final Map<String, GridLocator.Ref> refs = new HashMap<>();
        private String confirmRemove = null;
        private double confirmUntil = 0;

        private final class Hit {
            final Coord ul, sz;
            final Runnable action;
            final String tip;

            Hit(Coord ul, Coord sz, Runnable action, String tip) {
                this.ul = ul;
                this.sz = sz;
                this.action = action;
                this.tip = tip;
            }
        }

        TimerList(Coord sz) {
            super(sz);
        }

        private boolean passes(Timer t) {
            switch(filter) {
                case MINE: return TimerStore.isLocalCharacter(t.setBy);
                case VILLAGE: return t.shared;
                default: return true;
            }
        }

        @Override
        public void draw(GOut g) {
            hits.clear();
            CookbookTheme.fill(g, Coord.z, sz, CookbookTheme.bg);
            NGameUI gui = gui();
            TimerStore store = gui.timerStore;
            long now = System.currentTimeMillis();
            List<Timer> ready = new ArrayList<>();
            List<Timer> coming = new ArrayList<>();
            List<Timer> tasks = new ArrayList<>();
            int dismissed = 0;
            for(Timer t : store.timers()) {
                if(!passes(t) || store.isStale(t, now))
                    continue;
                if(t.kind == Timer.Kind.TASK) {
                    // Only the deadlines meant for this player: their tasks, or untaken ones they set.
                    if(TimerStore.wantsNotice(t) && taskOf(t) != null)
                        tasks.add(t);
                    continue;
                }
                if(t.isReady(now)) {
                    ready.add(t);
                    if(store.local(t.id).ackedStart == t.startedAt)
                        dismissed++;
                } else {
                    coming.add(t);
                }
            }
            // Unseen first, newest first; dismissed ones after them.
            ready.sort((a, b) -> {
                boolean da = store.local(a.id).ackedStart == a.startedAt;
                boolean db = store.local(b.id).ackedStart == b.startedAt;
                if(da != db)
                    return da ? 1 : -1;
                return Long.compare(b.readyAt(), a.readyAt());
            });
            coming.sort((a, b) -> Long.compare(a.readyAt(), b.readyAt()));
            tasks.sort((a, b) -> Long.compare(a.readyAt(), b.readyAt()));

            if(ready.isEmpty() && coming.isEmpty() && tasks.isEmpty()) {
                int y = UI.scale(20);
                for(String line : L10n.get("timers.panel.empty").split("\n")) {
                    Tex t = TimerIcons.text(CookbookTheme.body, line, CookbookTheme.muted);
                    g.image(t, Coord.of((sz.x - t.sz().x) / 2, y));
                    y += t.sz().y + UI.scale(2);
                }
                contentH = 0;
                return;
            }

            int y = -scroll;
            if(!ready.isEmpty()) {
                String head = L10n.get("timers.panel.ready", ready.size() - dismissed, dismissed);
                y = header(g, y, head, CookbookTheme.accent);
                for(int i = 0; i < ready.size(); i++)
                    y = row(g, y, i, ready.get(i), now);
            }
            if(!coming.isEmpty()) {
                y = header(g, y, L10n.get("timers.panel.coming"), CookbookTheme.muted);
                for(int i = 0; i < coming.size(); i++)
                    y = row(g, y, i, coming.get(i), now);
            }
            if(!tasks.isEmpty()) {
                y = header(g, y, L10n.get("tasks.panel.heading"), CookbookTheme.accent);
                for(int i = 0; i < tasks.size(); i++)
                    y = row(g, y, i, tasks.get(i), now);
            }
            contentH = y + scroll;
            scroll = Math.max(0, Math.min(scroll, contentH - sz.y));
        }

        private int header(GOut g, int y, String text, Color col) {
            g.image(TimerIcons.text(CookbookTheme.small, text.toUpperCase(), col), Coord.of(PAD, y + UI.scale(5)));
            return y + HH;
        }

        private int row(GOut g, int y, int idx, Timer t, long now) {
            if(y + RH < 0 || y > sz.y)
                return y + RH;
            NGameUI gui = gui();
            TimerStore store = gui.timerStore;
            TimerStore.Local l = store.local(t.id);
            boolean ready = t.isReady(now);
            boolean dismissed = ready && l.ackedStart == t.startedAt;
            CookbookTheme.fill(g, Coord.of(0, y), Coord.of(sz.x, RH), (idx % 2 == 0) ? NStyle.rowOdd : NStyle.rowEven);

            int icon = UI.scale(24);
            if(dismissed)
                g.chcolor(255, 255, 255, 128);
            TimerIcons.drawKindIcon(g, t, Coord.of(PAD, y + (RH - icon) / 2), icon);
            g.chcolor();

            // Buttons, right to left: notify toggle, dismiss or remove, restart.
            int bx = sz.x - PAD - B;
            int by = y + (RH - B) / 2;
            Coord bsz = Coord.of(B, B);
            Coord bell = Coord.of(bx, by);
            if(!l.notify)
                g.chcolor(255, 255, 255, 90);
            g.image(TimerIcons.timerIcon(), bell, bsz);
            g.chcolor();
            hits.add(new Hit(bell, bsz, () -> store.setNotify(t.id, !l.notify),
                L10n.get(l.notify ? "timers.tip.mute" : "timers.tip.unmute")));
            bx -= B + UI.scale(4);
            Coord mid = Coord.of(bx, by);
            boolean task = t.kind == Timer.Kind.TASK;
            nurgling.todo.TodoItem item = task ? taskOf(t) : null;
            if(task) {
                // A task is finished in the To-Do list, which takes its deadline with it.
                if(item != null && gui.todoStore.canEdit(item.listId)) {
                    CookbookTheme.frame(g, mid, bsz, TimerIcons.READY);
                    TimerIcons.check(g, mid, B);
                    hits.add(new Hit(mid, bsz, () -> gui.todoStore.setDone(t.taskId, true), L10n.get("tasks.tip.done")));
                }
            } else if(ready && !dismissed) {
                CookbookTheme.frame(g, mid, bsz, TimerIcons.READY);
                TimerIcons.check(g, mid, B);
                hits.add(new Hit(mid, bsz, () -> store.dismiss(t.id, System.currentTimeMillis()), L10n.get("timers.tip.dismiss")));
            } else {
                boolean arming = t.id.equals(confirmRemove) && Utils.rtime() < confirmUntil;
                if(arming)
                    CookbookTheme.fill(g, mid, bsz, new Color(200, 60, 50, 160));
                g.image(NStyle.removei[0], mid, bsz);
                hits.add(new Hit(mid, bsz, () -> remove(t), removeTip(t, arming)));
            }
            if(!task) {
                bx -= B + UI.scale(4);
                Coord rs = Coord.of(bx, by);
                g.image(TimerIcons.restartIcon(), rs, bsz);
                hits.add(new Hit(rs, bsz, () -> {
                    store.restart(t.id, System.currentTimeMillis());
                    store.rememberDuration(TimerStore.durationKey(t.kind, t.resType), t.durationMs);
                }, L10n.get("timers.tip.restart", TimerDurations.formatShort(t.durationMs))));
            }

            // Name, time, detail line and progress bar.
            int tx = PAD + icon + UI.scale(8);
            int tw = bx - UI.scale(6) - tx;
            Color fg = dismissed ? CookbookTheme.muted : CookbookTheme.fg;
            String right = ready ? "" : TimerDurations.format(t.remaining(now));
            Tex rt = right.isEmpty() ? null : TimerIcons.text(CookbookTheme.bold, right, CookbookTheme.accent);
            int nameW = tw - ((rt == null) ? 0 : rt.sz().x + UI.scale(6));
            String name = (item != null) ? item.title : TimerBanners.displayName(t);
            g.image(TimerIcons.text(CookbookTheme.bold, CookbookTheme.ellipsize(CookbookTheme.bold, name, nameW), fg),
                Coord.of(tx, y + UI.scale(3)));
            if(rt != null)
                g.image(rt, Coord.of(tx + tw - rt.sz().x, y + UI.scale(3)));
            String detail = CookbookTheme.ellipsize(CookbookTheme.small, detail(t, ready, dismissed, now), tw);
            g.image(TimerIcons.text(CookbookTheme.small, detail, CookbookTheme.muted), Coord.of(tx, y + UI.scale(19)));
            int barY = y + RH - UI.scale(5);
            CookbookTheme.fill(g, Coord.of(tx, barY), Coord.of(tw, UI.scale(3)), new Color(0x1a, 0x21, 0x22));
            CookbookTheme.fill(g, Coord.of(tx, barY), Coord.of((int) (tw * t.progress(now)), UI.scale(3)),
                ready ? TimerIcons.READY : CookbookTheme.accent);

            // The rest of the row: show it on the map, or open it for editing.
            hits.add(new Hit(Coord.of(0, y), Coord.of(bx - UI.scale(4), RH), () -> {
                if(task)
                    gui.openTodoTask(t.taskId);
                else if(!t.hasLocation() || !TimerPlacement.showOnMap(gui, t))
                    gui.showTimerPopover(TimerPopover.edit(gui, t));
            }, null));
            return y + RH;
        }

        /** The task behind a deadline, or null when this client cannot see it. */
        private nurgling.todo.TodoItem taskOf(Timer t) {
            NGameUI gui = gui();
            return (gui.todoStore == null) ? null : gui.todoStore.view().items.get(t.taskId);
        }

        private String detail(Timer t, boolean ready, boolean dismissed, long now) {
            List<String> parts = new ArrayList<>();
            if(t.kind == Timer.Kind.TASK) {
                nurgling.todo.TodoItem it = taskOf(t);
                parts.add(ready ? L10n.get("tasks.overdue", TimerDurations.format(now - t.readyAt()))
                    : L10n.get("tasks.due_at", TimerDurations.formatClock(t.readyAt(), L10n.get("timers.tomorrow"))));
                if(it != null && !TimerStore.isLocalCharacter(it.createdBy))
                    parts.add(L10n.get("tasks.from", it.createdBy));
                parts.add(t.assignee.isEmpty() ? L10n.get("tasks.nobody") : L10n.get("tasks.for", TimerStore.isLocalCharacter(t.assignee) ? L10n.get("timers.by_you") : t.assignee));
                if(dismissed)
                    parts.add(L10n.get("timers.dismissed"));
                return String.join(" \u00b7 ", parts);
            }
            if(ready)
                parts.add(L10n.get("timers.ready_ago", TimerDurations.format(now - t.readyAt())));
            else
                parts.add(L10n.get("timers.at", TimerDurations.formatClock(t.readyAt(), L10n.get("timers.tomorrow"))));
            parts.add(TimerStore.isLocalCharacter(t.setBy) ? L10n.get("timers.by_you") : t.setBy);
            if(t.kind != Timer.Kind.RESOURCE)
                parts.add(L10n.get("timers.banner.kind_" + t.kind.key()).toLowerCase());
            if(t.repeatMs > 0)
                parts.add(L10n.get("timers.repeats", TimerDurations.formatShort(t.repeatMs)));
            if(!t.shared)
                parts.add(L10n.get("timers.private"));
            if(dismissed)
                parts.add(L10n.get("timers.dismissed"));
            if(t.hasLocation() && !onMap(t))
                parts.add(L10n.get("timers.not_on_map_short"));
            if(t.legacyTc != null)
                parts.add(L10n.get("timers.not_on_map_short"));
            return String.join(" · ", parts);
        }

        private boolean onMap(Timer t) {
            GridLocator.Ref ref = refs.get(t.id);
            if(ref == null || ref.gid != t.gridId || ref.local.x != t.ox || ref.local.y != t.oy) {
                ref = new GridLocator.Ref(t.gridId, Coord.of(t.ox, t.oy));
                refs.put(t.id, ref);
            }
            GridLocator.resolve(gui(), ref);
            return ref.loc() != null;
        }

        private String removeTip(Timer t, boolean arming) {
            if(arming)
                return L10n.get("timers.tip.remove_confirm", t.setBy);
            return L10n.get(t.shared ? "timers.tip.remove_shared" : "timers.tip.remove");
        }

        /** Someone else's shared timer needs a second click: it goes for the whole village. */
        private void remove(Timer t) {
            boolean others = t.shared && !TimerStore.isLocalCharacter(t.setBy);
            if(others && !(t.id.equals(confirmRemove) && Utils.rtime() < confirmUntil)) {
                confirmRemove = t.id;
                confirmUntil = Utils.rtime() + 3;
                return;
            }
            confirmRemove = null;
            gui().timerStore.remove(t.id);
        }

        private Hit hitAt(Coord c) {
            for(Hit h : hits) {
                if(c.isect(h.ul, h.sz))
                    return h;
            }
            return null;
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            Hit h = hitAt(ev.c);
            if(h == null)
                return super.mousedown(ev);
            if(ev.b == 1) {
                h.action.run();
                return true;
            }
            return super.mousedown(ev);
        }

        @Override
        public boolean mousewheel(MouseWheelEvent ev) {
            scroll = Math.max(0, Math.min(scroll + ev.a * UI.scale(30), Math.max(0, contentH - sz.y)));
            return true;
        }

        @Override
        public Object tooltip(Coord c, Widget prev) {
            Hit h = hitAt(c);
            if(h != null && h.tip != null)
                return h.tip;
            return super.tooltip(c, prev);
        }
    }
}
