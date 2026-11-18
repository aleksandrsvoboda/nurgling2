package nurgling.widgets.login;

import haven.*;
import nurgling.i18n.L10n;
import nurgling.sessions.multilogin.LoginGroups;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The "Log in to several" groups as links on the login screen, under the form: one click logs the
 * whole group in. Only groups with at least one character picked are shown; the row wraps to the
 * scrim's width and is empty (zero height) when there are none.
 */
public class NGroupLinks extends Widget {
    private final LoginGroups store;
    private final Consumer<LoginGroups.Group> run;
    /* What the row was built from, to notice a rename or a new pick. */
    private String shownFrom = null;

    public NGroupLinks(int w, LoginGroups store, Consumer<LoginGroups.Group> run) {
        super(Coord.of(w, 0));
        this.store = store;
        this.run = run;
        refresh();
    }

    private List<LoginGroups.Group> shown() {
        List<LoginGroups.Group> ret = new ArrayList<>();
        for (LoginGroups.Group g : store.list()) {
            if (!store.picks(g).isEmpty())
                ret.add(g);
        }
        return ret;
    }

    private String source(List<LoginGroups.Group> groups) {
        StringBuilder sb = new StringBuilder();
        for (LoginGroups.Group g : groups)
            sb.append(g.name).append('\u0000');
        return sb.toString();
    }

    /** Rebuilds the row if the groups changed; true if its height changed. */
    public boolean refresh() {
        List<LoginGroups.Group> groups = shown();
        String src = source(groups);
        if (src.equals(shownFrom))
            return false;
        shownFrom = src;
        for (Widget c = child; c != null; ) {
            Widget next = c.next;
            c.reqdestroy();
            c = next;
        }
        int oldh = sz.y;
        if (groups.isEmpty()) {
            resize(Coord.of(sz.x, 0));
            return oldh != 0;
        }
        ILabel label = add(new ILabel(L10n.get("multilogin.groups_label"), NLoginTheme.label), Coord.z);
        int rowh = 0;
        List<NActionLink> links = new ArrayList<>();
        for (LoginGroups.Group g : groups) {
            NActionLink l = new NActionLink(g.name, () -> run.accept(g));
            links.add(l);
            rowh = Math.max(rowh, l.sz.y);
        }
        int x0 = label.sz.x + UI.scale(10), x = x0, y = 0;
        label.move(Coord.of(0, (rowh - label.sz.y) / 2));
        for (NActionLink l : links) {
            if (x > x0 && x + l.sz.x > sz.x) {
                x = x0;
                y += rowh + UI.scale(4);
            }
            add(l, Coord.of(x, y));
            x += l.sz.x + UI.scale(16);
        }
        resize(Coord.of(sz.x, y + rowh));
        return oldh != sz.y;
    }
}
