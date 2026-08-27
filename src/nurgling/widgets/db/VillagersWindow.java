package nurgling.widgets.db;

import haven.*;
import haven.Button;
import haven.Label;
import nurgling.NConfig;
import nurgling.NCore;
import nurgling.db.ConnectionString;
import nurgling.db.service.VillagerService;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Who can use this village's database.
 *
 * <p>Until now the only way to add a player was {@code psql}, so nobody did, and villages shared one
 * superuser login. Everything here is ordinary SQL over the existing connection, so it works against
 * any PostgreSQL the client can reach - a container this client started, or a server that has been
 * running for a year.
 */
public class VillagersWindow extends Window {
    private static final Coord WINDOW_SIZE = UI.scale(new Coord(600, 440));

    private final List<VillagerService.Villager> villagers = new ArrayList<>();

    /** Row geometry, measured rather than guessed - see {@link #measureRowHeight}. */
    private static final int BUTTON_W = UI.scale(72);
    private static final int GAP = UI.scale(6);
    private final int rowHeight = measureRowHeight();
    private final Label status;
    private final Label hint;
    private final TextEntry newName;
    private final Dropbox<String> newAccess;
    private final TextEntry inviteOut;
    private final Button addButton;
    private final Button repairButton;

    private String newAccessValue = VillagerService.ACCESS_MEMBER;

    /* Results arrive on database threads; widgets are only ever touched from tick(). */
    private volatile List<VillagerService.Villager> pending = null;
    private volatile String pendingStatus = null;
    private volatile Color pendingStatusColor = Color.WHITE;
    private volatile String pendingInvite = null;
    private volatile boolean busy = false;
    private volatile Boolean admin = null;

    public VillagersWindow() {
        super(WINDOW_SIZE, "Villagers");
        int y = UI.scale(5);

        status = add(new Label(""), new Coord(UI.scale(5), y));
        y += UI.scale(18);
        hint = add(new Label(""), new Coord(UI.scale(5), y));
        y += UI.scale(20);

        add(new VillagerList(new Coord(WINDOW_SIZE.x - UI.scale(20), UI.scale(224)), rowHeight),
            new Coord(UI.scale(5), y));
        y += UI.scale(232);

        add(new Label("Add:"), new Coord(UI.scale(5), y + UI.scale(3)));
        newName = add(new TextEntry(UI.scale(150), ""), new Coord(UI.scale(45), y));
        newAccess = add(new Dropbox<String>(UI.scale(110), 2, UI.scale(16)) {
            protected String listitem(int i) {
                return i == 0 ? "Villager" : "Guest (read-only)";
            }

            protected int listitems() {
                return 2;
            }

            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }

            public void change(String item) {
                super.change(item);
                newAccessValue = item.startsWith("Guest")
                    ? VillagerService.ACCESS_GUEST : VillagerService.ACCESS_MEMBER;
            }
        }, new Coord(UI.scale(205), y));
        newAccess.change("Villager");

        addButton = add(new Button(UI.scale(110), "Add villager") {
            public void click() {
                super.click();
                addVillager();
            }
        }, new Coord(UI.scale(325), y));

        repairButton = add(new Button(UI.scale(140), "Repair permissions") {
            public void click() {
                super.click();
                repair();
            }
        }, new Coord(UI.scale(445), y));
        repairButton.tooltip = Text.render(
            "Re-grants every table and sequence. Safe to press at any time, including while "
          + "people are playing.").tex();
        /* Tallest member of the row, not whichever one happened to get named here: a dropbox and a
         * button do not agree on height, and assuming one of them is what clipped the row buttons. */
        int addRowH = Math.max(Math.max(newName.sz.y, newAccess.sz.y),
                               Math.max(addButton.sz.y, repairButton.sz.y));
        y += addRowH + UI.scale(8);

        add(new Label("Connect:"), new Coord(UI.scale(5), y + UI.scale(3)));
        inviteOut = add(new TextEntry(UI.scale(430), ""), new Coord(UI.scale(55), y));
        Button copyButton = add(new Button(UI.scale(90), "Copy") {
            public void click() {
                super.click();
                if (!inviteOut.text().isEmpty() && DbClipboard.copy(inviteOut.text()))
                    setStatus("Copied. Send it to that player privately - it contains their password.",
                              Color.YELLOW);
            }
        }, new Coord(UI.scale(495), y));

        /* Trim to what the contents actually came to. WINDOW_SIZE is only an upper bound, so a font
         * or a button graphic that measures larger than expected still fits rather than being cut
         * off, and one that measures smaller does not leave a band of empty window behind it. */
        int inviteRowH = Math.max(inviteOut.sz.y, copyButton.sz.y);
        resize(new Coord(WINDOW_SIZE.x, y + inviteRowH + UI.scale(6)));

        refresh();
    }


    /**
     * The X on the title bar.
     *
     * <p>A Window built by the client, rather than sent by the server, gets no reply to its close
     * message - so without this the button does nothing at all. Hiding rather than destroying is
     * what lets the settings panel hand back the same window instead of stacking up new ones.
     */
    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if (msg.equals("close")) {
            hide();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    @Override
    public void show() {
        // Reopened after being hidden: accounts may have changed on another client meanwhile.
        refresh();
        super.show();
    }

    // ---- actions -------------------------------------------------------------------------

    private VillagerService service() {
        return (NCore.databaseManager == null) ? null : NCore.databaseManager.getVillagerService();
    }

    private boolean ready() {
        if (NCore.databaseManager == null || !NCore.databaseManager.isReady()) {
            setStatus("Not connected to a village database.", Color.ORANGE);
            return false;
        }
        return service() != null;
    }

    public void refresh() {
        if (!ready())
            return;
        busy = true;
        service().isAdminAsync().thenAccept(isAdmin -> admin = isAdmin);
        service().listAsync()
            .thenAccept(list -> {
                pending = list;
                busy = false;
            })
            .exceptionally(e -> {
                setStatus("Could not read the account list: " + rootMessage(e), Color.ORANGE);
                busy = false;
                return null;
            });
    }

    private void addVillager() {
        if (!ready() || busy)
            return;
        final String name;
        try {
            name = VillagerService.requireIdentifier(newName.text());
        } catch (IllegalArgumentException e) {
            setStatus(e.getMessage(), Color.ORANGE);
            return;
        }
        busy = true;
        final String access = newAccessValue;
        service().addAsync(name, access)
            .thenAccept(account -> {
                pendingInvite = buildConnectionString(account);
                setStatus("Added " + account.name
                        + ". Copy the connection string below and send it to them privately.",
                          Color.GREEN);
                busy = false;
                refresh();
            })
            .exceptionally(e -> {
                setStatus("Could not add " + name + ": " + rootMessage(e), Color.ORANGE);
                busy = false;
                return null;
            });
    }

    private void resetPassword(String name) {
        if (!ready() || busy)
            return;
        busy = true;
        service().resetPasswordAsync(name)
            .thenAccept(account -> {
                pendingInvite = buildConnectionString(account);
                setStatus("New password for " + account.name
                        + ". Their old connection string no longer works.", Color.GREEN);
                busy = false;
                refresh();
            })
            .exceptionally(e -> {
                setStatus("Could not reset " + name + ": " + rootMessage(e), Color.ORANGE);
                busy = false;
                return null;
            });
    }

    private void delete(String name) {
        if (!ready() || busy)
            return;
        busy = true;
        service().deleteAsync(name)
            .thenAccept(outcome -> {
                /* Said plainly rather than implied: this stops future access and nothing else.
                 * Whatever they synced is on their disk already. */
                if (outcome == VillagerService.DeleteOutcome.DELETED) {
                    setStatus("Deleted " + name + ". They keep whatever they already synced to their PC.",
                              Color.YELLOW);
                } else {
                    setStatus("Disabled " + name + " - the account still owns something, so it could "
                            + "not be dropped. It can no longer log in.", Color.YELLOW);
                }
                busy = false;
                refresh();
            })
            .exceptionally(e -> {
                setStatus("Could not delete " + name + ": " + rootMessage(e), Color.ORANGE);
                busy = false;
                return null;
            });
    }

    private void repair() {
        if (!ready() || busy)
            return;
        busy = true;
        service().repairAsync()
            .thenAccept(v -> {
                setStatus("Permissions repaired.", Color.GREEN);
                busy = false;
                refresh();
            })
            .exceptionally(e -> {
                setStatus("Could not repair permissions: " + rootMessage(e), Color.ORANGE);
                busy = false;
                return null;
            });
    }

    /**
     * The string this villager pastes to connect.
     *
     * <p>Built from the admin's own connection, which is the point: they are already connected, so
     * the address and port cannot be mistyped or forgotten on the way. The port in particular is
     * the one that gets lost, because the settings field that carries it is labelled "Host".
     */
    private static String buildConnectionString(VillagerService.NewAccount account) {
        return ConnectionString.build(str(NConfig.get(NConfig.Key.serverNode)),
                                      ConnectionString.DEFAULT_DATABASE,
                                      account.name, account.password);
    }

    private static String str(Object o) {
        return (o == null) ? "" : o.toString();
    }

    // ---- ui ------------------------------------------------------------------------------

    private void setStatus(String text, Color color) {
        pendingStatus = text;
        pendingStatusColor = color;
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);

        String st = pendingStatus;
        if (st != null) {
            status.settext(st);
            status.setcolor(pendingStatusColor);
            pendingStatus = null;
        }

        String inv = pendingInvite;
        if (inv != null) {
            inviteOut.settext(inv);
            pendingInvite = null;
        }

        List<VillagerService.Villager> list = pending;
        if (list != null) {
            pending = null;
            rebuild(list);
        }

        Boolean isAdmin = admin;
        if (isAdmin != null) {
            boolean can = isAdmin;
            addButton.visible = can;
            repairButton.visible = can;
            newName.visible = can;
            newAccess.visible = can;
            if (!can) {
                hint.settext("Only the host can add or remove villagers.");
                hint.setcolor(Color.LIGHT_GRAY);
            } else {
                int waiting = 0;
                synchronized (villagers) {
                    for (VillagerService.Villager v : villagers) {
                        if (v.neverConnected() && !v.name.equals(currentUser()))
                            waiting++;
                    }
                }
                /* Doubles as the migration tracker: a village retiring a shared login needs to know
                 * that everyone has actually moved before that password is rotated. */
                hint.settext(waiting == 0
                    ? "Everyone listed has connected on their own account."
                    : waiting + " account(s) have never connected yet.");
                hint.setcolor(waiting == 0 ? Color.GREEN : Color.YELLOW);
            }
        }
    }

    private static String currentUser() {
        return str(NConfig.get(NConfig.Key.serverUser));
    }

    private void rebuild(List<VillagerService.Villager> list) {
        synchronized (villagers) {
            villagers.clear();
            villagers.addAll(list);
        }
    }

    /**
     * How tall one row has to be to hold a button.
     *
     * <p>{@link Button} picks its own height from its width - a wide button gets the tall graphic -
     * so a hand-picked row height silently clipped the bottom off Reset and Remove. Building one
     * throwaway button of exactly the width the rows use is the only way to know what Haven will
     * actually produce, at any UI scale, without duplicating its private sizing rule.
     */
    private static int measureRowHeight() {
        Button probe = new Button(BUTTON_W, "");
        return Math.max(probe.sz.y, Text.std.height()) + UI.scale(4);
    }

    /** Trim to fit a column, so a long name cannot run under the one beside it. */
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

    /** "3 days ago", or the plain truth when they have never used the account. */
    static String ago(java.sql.Timestamp ts) {
        if (ts == null)
            return "never";
        long secs = (System.currentTimeMillis() - ts.getTime()) / 1000L;
        if (secs < 90)
            return "just now";
        if (secs < 3600)
            return (secs / 60) + " min ago";
        if (secs < 86400)
            return (secs / 3600) + " h ago";
        return (secs / 86400) + " d ago";
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c)
            c = c.getCause();
        String m = c.getMessage();
        return (m == null || m.isEmpty()) ? String.valueOf(c) : m;
    }

    // ---- rows ----------------------------------------------------------------------------

    public class Row extends Widget {
        final VillagerService.Villager villager;

        /**
         * @param sz the size the list actually allotted this row - which is why the columns and the
         *           buttons are derived from it rather than from pixel offsets picked by eye. The
         *           list reserves room for its scrollbar, so the usable width is not the window's.
         */
        Row(VillagerService.Villager v, Coord sz) {
            super(sz);
            this.villager = v;

            boolean isAdmin = VillagerService.ACCESS_ADMIN.equals(v.access);

            int removeX = sz.x - GAP - BUTTON_W;
            int resetX = removeX - GAP - BUTTON_W;
            int textLeft = GAP;
            // Admins have no buttons, so their text may use the whole row.
            int textRight = (isAdmin ? sz.x : resetX) - GAP;
            int textWidth = Math.max(textRight - textLeft, UI.scale(60));

            int nameW = (textWidth * 45) / 100;
            int accessW = (textWidth * 22) / 100;
            int seenW = textWidth - nameW - accessW;

            Label name = addCentered(new Label(clip(v.name, nameW - GAP)), textLeft, sz.y);
            if (!v.canLogin)
                name.setcolor(Color.GRAY);

            Label access = addCentered(new Label(clip(label(v.access), accessW - GAP)),
                                       textLeft + nameW, sz.y);
            if (isAdmin)
                access.setcolor(Color.YELLOW);

            Label seen = addCentered(new Label(clip(ago(v.lastSeen), seenW - GAP)),
                                     textLeft + nameW + accessW, sz.y);
            if (v.neverConnected())
                seen.setcolor(Color.LIGHT_GRAY);

            if (!isAdmin) {
                /* Positioned from each button's own height once it exists, rather than from an
                 * assumed one - Button decides its height itself, and that assumption is exactly
                 * what clipped these before. */
                Button reset = add(new Button(BUTTON_W, "Reset") {
                    public void click() {
                        super.click();
                        resetPassword(villager.name);
                    }
                }, Coord.z);
                reset.move(new Coord(resetX, Math.max(0, (sz.y - reset.sz.y) / 2)));
                reset.tooltip =
                    Text.render("Issue a new password. Their current invite stops working.").tex();

                Button remove = add(new Button(BUTTON_W, v.canLogin ? "Delete" : "Disabled") {
                    public void click() {
                        super.click();
                        if (villager.canLogin)
                            delete(villager.name);
                    }
                }, Coord.z);
                remove.move(new Coord(removeX, Math.max(0, (sz.y - remove.sz.y) / 2)));
                remove.tooltip =
                    Text.render("Removes the account. Does not remove what they already synced.").tex();
            }
        }

        /** Add a label vertically centred in the row, whatever the font ends up measuring. */
        private Label addCentered(Label label, int x, int rowH) {
            add(label, new Coord(x, Math.max(0, (rowH - label.sz.y) / 2)));
            return label;
        }

        private String label(String access) {
            if (VillagerService.ACCESS_ADMIN.equals(access))
                return "Admin";
            if (VillagerService.ACCESS_GUEST.equals(access))
                return "Guest";
            return "Villager";
        }

        @Override
        public void draw(GOut g) {
            /* A faint stripe behind anyone who has never used their account: this list doubles as
               the tracker for retiring a shared login, and that is the state that matters. */
            if (villager.neverConnected() && !VillagerService.ACCESS_ADMIN.equals(villager.access)) {
                g.chcolor(128, 128, 0, 24);
                g.frect(Coord.z, sz);
                g.chcolor();
            }
            super.draw(g);
        }
    }

    public class VillagerList extends SListBox<VillagerService.Villager, Widget> {
        VillagerList(Coord sz, int itemh) {
            super(sz, itemh);
        }

        protected List<VillagerService.Villager> items() {
            synchronized (villagers) {
                return new LinkedList<>(villagers);
            }
        }

        /* Built here rather than up front because this is the first point at which the row's real
         * width is known - the list subtracts its own scrollbar from the space it was given. */
        protected Widget makeitem(VillagerService.Villager item, int idx, Coord sz) {
            return new ItemWidget<VillagerService.Villager>(this, sz, item) {
                {
                    add(new Row(item, sz));
                }
            };
        }
    }
}
