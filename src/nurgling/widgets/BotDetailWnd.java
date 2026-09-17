package nurgling.widgets;

import haven.*;
import nurgling.i18n.L10n;
import nurgling.widgets.db.DbClipboard;

import java.util.ArrayList;
import java.util.List;

/** Opened by clicking a Bot Status row: the full breadcrumb, the waited-on task and the raw action frames. */
public class BotDetailWnd extends Window {
    private static final int W = UI.scale(420);
    private static final int LINE = UI.scale(16);
    private static final int MAX_FRAMES = 12;
    private static final int LINES = 6 + MAX_FRAMES;

    private final BotsInterruptWidget.Gear gear;
    private final Text[] lines = new Text[LINES];

    public BotDetailWnd(BotsInterruptWidget.Gear gear) {
        super(new Coord(W, LINE * LINES + UI.scale(30)), gear.title);
        this.gear = gear;
        Widget body = add(new Widget(new Coord(W, LINE * LINES)) {
            @Override
            public void draw(GOut g) {
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i] != null)
                        g.image(lines[i].tex(), new Coord(0, i * LINE));
                }
            }
        }, Coord.z);
        Widget copy = add(new Button(UI.scale(140), L10n.get("botstatus.copy"), false, this::copyStack), body.pos("bl").adds(0, 6));
        add(new Button(UI.scale(80), L10n.get("botstatus.stop_button"), false, gear::stop), copy.pos("ur").adds(8, 0));
        pack();
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        List<String> rows = new ArrayList<>();
        rows.add(L10n.get("botstatus.running", BotsInterruptWidget.elapsed(System.currentTimeMillis() - gear.started)));
        rows.add(L10n.get("botstatus.step"));
        rows.add("  " + gear.crumbText(BotsInterruptWidget.linef, W - UI.scale(10)));
        rows.add(L10n.get("botstatus.waiting_on"));
        rows.add("  " + (gear.waiting == null ? "—" : gear.waiting));
        rows.add(L10n.get("botstatus.frames"));
        for (int i = 0; i < Math.min(MAX_FRAMES, gear.frames.size()); i++) {
            StackTraceElement e = gear.frames.get(i);
            rows.add("  " + BotsInterruptWidget.simpleName(e.getClassName()) + "." + e.getMethodName() + ":" + e.getLineNumber());
        }
        for (int i = 0; i < lines.length; i++) {
            boolean heading = i == 1 || i == 3 || i == 5;
            lines[i] = BotsInterruptWidget.rerender(lines[i], i < rows.size() ? rows.get(i) : null,
                    heading ? BotsInterruptWidget.waitf : BotsInterruptWidget.linef);
        }
    }

    private void copyStack() {
        StringBuilder sb = new StringBuilder(gear.t.getName()).append('\n');
        for (StackTraceElement e : gear.t.getStackTrace())
            sb.append("\tat ").append(e).append('\n');
        DbClipboard.copy(sb.toString());
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if (msg.equals("close")) {
            gear.detail = null;
            reqdestroy();
        } else {
            super.wdgmsg(msg, args);
        }
    }

    @Override
    public void dispose() {
        for (Text t : lines) {
            if (t != null)
                t.dispose();
        }
        super.dispose();
    }
}
