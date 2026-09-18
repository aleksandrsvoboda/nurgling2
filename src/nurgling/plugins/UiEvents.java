package nurgling.plugins;

import haven.UI;
import haven.Widget;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Generic event seam that lets plugins observe widget traffic between the client and the server:
 * widgets the server creates, attaches, messages and destroys, and every message a widget sends
 * back. It carries no behavior of its own.
 *
 * Incoming events fire on the UI thread when the command is applied, so the widget already
 * exists. Outgoing events fire on whichever thread sent the message (the UI thread for user
 * input, a bot thread for automated actions). Listeners must be quick and must not block.
 */
public class UiEvents {

    /** Every method is optional; implement only what you need. */
    public interface Listener {
        /** The server created widget {@code id}; {@code type} is its type name, e.g. "wnd" or "ui/xxx:3". */
        default void onNewWidget(UI ui, int id, String type, Widget wdg, Object[] cargs) {}

        /** The server attached widget {@code id} to {@code parent}. */
        default void onAddWidget(UI ui, int id, Widget wdg, int parent, Widget pwdg, Object[] pargs) {}

        /** The server sent a message to widget {@code id}. */
        default void onUiMsg(UI ui, int id, Widget wdg, String msg, Object[] args) {}

        /** The server destroyed widget {@code id}. */
        default void onDestroyWidget(UI ui, int id, Widget wdg) {}

        /** Widget {@code id} sent a message to the server. */
        default void onOutgoing(UI ui, int id, Widget sender, String msg, Object[] args) {}
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public static void addListener(Listener l) {
        if (l != null) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Lets core skip building event arguments when nobody listens. */
    public static boolean active() {
        return !listeners.isEmpty();
    }

    public static void fireNewWidget(UI ui, int id, String type, Widget wdg, Object[] cargs) {
        for (Listener l : listeners) {
            try {
                l.onNewWidget(ui, id, type, wdg, cargs);
            } catch (RuntimeException e) {
                // A misbehaving plugin must not break widget handling.
                e.printStackTrace();
            }
        }
    }

    public static void fireAddWidget(UI ui, int id, Widget wdg, int parent, Widget pwdg, Object[] pargs) {
        for (Listener l : listeners) {
            try {
                l.onAddWidget(ui, id, wdg, parent, pwdg, pargs);
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
    }

    public static void fireUiMsg(UI ui, int id, Widget wdg, String msg, Object[] args) {
        for (Listener l : listeners) {
            try {
                l.onUiMsg(ui, id, wdg, msg, args);
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
    }

    public static void fireDestroyWidget(UI ui, int id, Widget wdg) {
        for (Listener l : listeners) {
            try {
                l.onDestroyWidget(ui, id, wdg);
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
    }

    public static void fireOutgoing(UI ui, int id, Widget sender, String msg, Object[] args) {
        for (Listener l : listeners) {
            try {
                l.onOutgoing(ui, id, sender, msg, args);
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
    }
}
