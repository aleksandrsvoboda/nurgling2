package nurgling.widgets;

import haven.*;
import nurgling.*;
import nurgling.conf.*;
import nurgling.i18n.L10n;

import java.util.*;

public class NBuddyWnd extends BuddyWnd
{
    /** Prefixed onto the last-seen text of any kin that carries a note. */
    private static final String NOTEMARK = "• ";
    private static final Text.Foundry tipf = new Text.Foundry(Text.sans, 12).aa(true);

    ICheckBox settings;
    NKinSettings ks = null;
    final Coord shift = UI.scale(16,5);

    private NKinNotes notes = null;
    /** Last string actually rendered per kin, so the per-tick refresh stops re-rasterising
     *  the same text (and allocating a texture for it) every single frame. */
    private final Map<Integer, String> lastol = new HashMap<>();
    private final Map<String, Text> tipcache = new HashMap<>();

    public NBuddyWnd()
    {
        add(settings = new ICheckBox(NStyle.settingsi[0], NStyle.settingsi[1], NStyle.settingsi[2], NStyle.settingsi[3])
        {
            @Override
            public void changed(boolean val)
            {
                super.changed(val);
                if(val)
                {
                    if(ks == null)
                    {
                        ks = new NKinSettings(settings);
                        ui.root.add(ks, NUtils.getGameUI().zerg.rootpos());
                    }
                    ks.show();
                    ks.raise();
                    ks.move(NUtils.getGameUI().zerg.rootpos().sub(UI.scale(0,50)));
                }
                else
                    ks.hide();
            }
        }, new Coord(sz.x - NStyle.settingsi[0].sz().x / 2, NStyle.settingsi[0].sz().y / 2).sub(shift));

        pack();

    }

    /** Per-world note store for the session this window belongs to. */
    public NKinNotes notes()
    {
        if(notes == null)
        {
            String genus = null;
            GameUI gui = getparent(GameUI.class);
            if(gui instanceof NGameUI)
                genus = ((NGameUI) gui).getGenus();
            if(genus == null)
                return(null);
            notes = NKinNotes.get(genus);
        }
        return(notes);
    }

    @Override
    protected BuddyInfo makeinfo(Coord sz, Buddy buddy)
    {
        return(new NBuddyInfo(sz, buddy));
    }

    /**
     * The vanilla info panel plus a note box pinned to its bottom. The box is placed below
     * whatever the panel's own widgets occupy - which changes with the number of kinship
     * options - rather than at a guessed offset.
     */
    public class NBuddyInfo extends BuddyInfo
    {
        private final Label lbl;
        private final NTextArea note;
        private double dirty = 0;
        /** Until the store has actually been read, an empty box means "unknown", not "blank" -
         *  flushing it would wipe a note that simply had not loaded yet. */
        private boolean loaded = false;

        public NBuddyInfo(Coord sz, Buddy buddy)
        {
            super(sz, buddy);
            lbl = add(new Label(L10n.get("kin.note")), Coord.of(margin2, 0));
            note = add(new NTextArea(Coord.of(sz.x - margin3, UI.scale(20)), ""), Coord.of(margin2, 0));
            note.onchange = () -> {if(loaded) dirty = Utils.rtime();};
            note.oncommit = this::flush;
            ckload();
            layoutnote();
        }

        private void ckload()
        {
            if(loaded)
                return;
            NKinNotes ns = notes();
            if(ns == null)
                return;
            note.settext(ns.get(buddy.id, buddy.name));
            loaded = true;
        }

        private void layoutnote()
        {
            int bot = 0;
            for(Widget w = child; w != null; w = w.next)
            {
                if((w == note) || (w == lbl))
                    continue;
                bot = Math.max(bot, w.c.y + w.sz.y);
            }
            int top = bot + margin2;
            int h = sz.y - top - lbl.sz.y - UI.scale(2) - margin2;
            if(h < UI.scale(28))
            {
                lbl.hide();
                note.hide();
                return;
            }
            lbl.show();
            note.show();
            lbl.c = Coord.of(margin2, top);
            note.c = Coord.of(margin2, top + lbl.sz.y + UI.scale(2));
            if(note.sz.y != h)
                note.resize(Coord.of(sz.x - margin3, h));
        }

        public void flush()
        {
            dirty = 0;
            if(!loaded)
                return;
            NKinNotes ns = notes();
            if(ns != null)
                ns.set(buddy.id, buddy.name, note.text());
        }

        @Override
        public void tick(double dt)
        {
            super.tick(dt);
            ckload();
            layoutnote();
            if((dirty > 0) && (Utils.rtime() - dirty > 0.4))
                flush();
        }

        @Override
        public void destroy()
        {
            if(dirty > 0)
                flush();
            super.destroy();
        }
    }

    private Text tip(String note, String name)
    {
        String text = name + "\n\n" + note;
        Text ret = tipcache.get(text);
        if(ret == null)
        {
            if(tipcache.size() > 64)
            {
                for(Text t : tipcache.values())
                    t.dispose();
                tipcache.clear();
            }
            tipcache.put(text, ret = tipf.renderwrap(text, UI.scale(240)));
        }
        return(ret);
    }

    /**
     * Kin rows are drawn by an anonymous widget in haven that never calls super.draw(), so a
     * note marker cannot be a child widget. The last-seen text is regenerated here every tick
     * anyway, so the marker rides along with it, and the note itself goes on the row tooltip.
     */
    private void marknotes()
    {
        NKinNotes ns = notes();
        if(ns == null)
            return;
        for(Widget w = child; w != null; w = w.next)
        {
            if(!(w instanceof SSearchBox))
                continue;
            for(Widget r = w.child; r != null; r = r.next)
            {
                if(!(r instanceof SListWidget.ItemWidget))
                    continue;
                Object item = ((SListWidget.ItemWidget<?>) r).item;
                if(!(item instanceof Buddy))
                    continue;
                Buddy b = (Buddy) item;
                String note = ns.get(b.id, b.name);
                r.tooltip = note.isEmpty() ? null : tip(note, b.name);
            }
        }
    }

    final Set<Integer> req = new HashSet<>();
    @Override
    public void tick(double dt)
    {
        super.tick(dt);
        double now = Utils.rtime();
        if(NUtils.getGameUI()!=null && NUtils.getGameUI().zerg!=null && NUtils.getGameUI().zerg.visible && parent.visible)
        {
            synchronized (req)
            {
                int count = 0;
                if (req.isEmpty())
                    for (Buddy b : buddies)
                    {
                        if ((now - b.upTime > 10 || b.upTime == 0) && count++<7)
                        {
                            wdgmsg("ch", b.id);
                            req.add(b.id);
                            b.upTime = now;
                        }
                    }
            }
            NKinNotes ns = notes();
            for (Buddy b : buddies)
            {
                String text = lastOnline(b.atime, b, null);
                if((ns != null) && ns.has(b.id, b.name))
                    text = NOTEMARK + text;
                if(!text.equals(lastol.get(b.id)) || b.lastOnline == null)
                {
                    lastol.put(b.id, text);
                    if(b.lastOnline != null)
                        b.lastOnline.dispose();
                    b.lastOnline = Text.render(text);
                }
            }
            marknotes();
        }
    }

    int lastSet = -1;

    @Override
    public void uimsg(String msg, Object... args)
    {
        synchronized (req)
        {
            if(!req.isEmpty() )
            {
                if (msg.equals("i-set"))
                {
                    if (req.contains((int) args[0]))
                    {
                        lastSet = (int) args[0];
                        req.remove((int) args[0]);
                        return;
                    }
                }
            }
            if(lastSet!=-1)
            {
                if(msg.equals("i-atime"))
                {
                    for(Buddy b : buddies)
                    {
                        if(b.id == lastSet)
                        {
                            b.atime = (long)Utils.ntime() - ((Number)args[0]).longValue();
                            lastSet = -1;
                            return;
                        }
                    }
                }
                if(msg.equals("i-ava"))
                {
                    return;
                }
            }
        }
        super.uimsg(msg, args);
    }

}
