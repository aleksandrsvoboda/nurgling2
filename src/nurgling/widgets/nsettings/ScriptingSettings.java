package nurgling.widgets.nsettings;

import haven.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.i18n.L10n;
import nurgling.scripting.PythonFinder;
import nurgling.scripting.ScriptCatalog;
import nurgling.scripting.ScriptRuntime;
import nurgling.scripting.ScriptServer;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Options > Scripting: turns the local scripting server on and off and says where Python and the
 * scripts are. Like the other settings pages, changes apply when the player presses Save.
 */
public class ScriptingSettings extends Panel {
    public static final String DOCS_URL = "https://aleksandrsvoboda.github.io/nurgling2/";

    private final CheckBox enable;
    private final TextEntry python;
    private final TextEntry port;
    private final TextEntry dir;
    private final Label testResult;
    private final Label dirResolved;
    private final Label status;
    private final Label note;
    private Window warning;

    // Written by helper threads, shown by tick() on the UI thread.
    private volatile String pendingTest = null;
    private volatile String pendingNote = null;

    public ScriptingSettings() {
        super(L10n.get("scripting.title"));
        int x = UI.scale(10);
        int y = UI.scale(40);

        add(new Label(L10n.get("scripting.description")), x, y);
        y += UI.scale(30);

        enable = add(new CheckBox(L10n.get("scripting.enable")) {
            @Override
            public void set(boolean a) {
                if (a && !Boolean.TRUE.equals(NConfig.get(NConfig.Key.scriptingWarned))) {
                    showWarning();
                    return;
                }
                super.set(a);
            }
        }, x, y);
        y += UI.scale(30);

        add(new Label(L10n.get("scripting.python")), x, y);
        y += UI.scale(20);
        python = add(new TextEntry(UI.scale(440), ""), x, y);
        add(new Button(UI.scale(110), L10n.get("scripting.test"), this::testPython), x + UI.scale(450), y - UI.scale(2));
        y += UI.scale(26);
        testResult = add(new Label(""), x, y);
        y += UI.scale(30);

        add(new Label(L10n.get("scripting.port")), x, y);
        y += UI.scale(20);
        port = add(new TextEntry(UI.scale(100), ""), x, y);
        y += UI.scale(36);

        add(new Label(L10n.get("scripting.dir")), x, y);
        y += UI.scale(20);
        dir = add(new TextEntry(UI.scale(560), ""), x, y);
        y += UI.scale(26);
        dirResolved = add(new Label(""), x, y);
        dirResolved.setcolor(Color.LIGHT_GRAY);
        y += UI.scale(24);
        Button open = add(new Button(UI.scale(150), L10n.get("scripting.open_folder"), this::openFolder), x, y);
        add(new Button(UI.scale(150), L10n.get("scripting.rescan"), this::rescan), open.pos("ur").adds(UI.scale(10), 0));
        y += UI.scale(40);

        status = add(new Label(""), x, y);
        y += UI.scale(24);
        note = add(new Label(""), x, y);
        y += UI.scale(30);

        add(new Button(UI.scale(150), L10n.get("scripting.docs"), this::openDocs), x, y);

        pack();
    }

    @Override
    public void load() {
        enable.a = ScriptRuntime.enabled();
        python.settext(str(NConfig.get(NConfig.Key.scriptingPython)));
        Object p = NConfig.get(NConfig.Key.scriptingPort);
        port.settext(p instanceof Number ? String.valueOf(((Number) p).intValue()) : "0");
        dir.settext(str(NConfig.get(NConfig.Key.scriptingDir)));
        testResult.settext("");
        note.settext("");
    }

    @Override
    public void save() {
        boolean was = ScriptRuntime.enabled();
        String oldDir = str(NConfig.get(NConfig.Key.scriptingDir));
        boolean on = enable.a;

        NConfig.set(NConfig.Key.scriptingPython, python.text().trim());
        Integer portNo = parsePort(port.text());
        if (portNo != null)
            NConfig.set(NConfig.Key.scriptingPort, portNo);
        else
            port.settext(String.valueOf(NConfig.get(NConfig.Key.scriptingPort)));
        String newDir = dir.text().trim();
        NConfig.set(NConfig.Key.scriptingDir, newDir);
        NConfig.set(NConfig.Key.scriptingEnabled, on);
        NConfig.needUpdate();

        if (on && !was) {
            ScriptRuntime.ensureScriptsDir();
            if (ScriptRuntime.ensureStarted() == null)
                note.settext(L10n.get("scripting.start_failed"));
            ScriptCatalog.refresh();
            syncMenus();
        } else if (!on && was) {
            ScriptRuntime.stop();
            syncMenus();
        } else if (on && !newDir.equals(oldDir)) {
            ScriptRuntime.ensureScriptsDir();
            ScriptCatalog.refresh();
            syncMenus();
        }
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        // Settings pages stay in the widget tree while another page shows; only the visible one refreshes.
        if (!tvisible())
            return;
        String t = pendingTest;
        if (t != null) {
            pendingTest = null;
            testResult.settext(t);
        }
        String n = pendingNote;
        if (n != null) {
            pendingNote = null;
            note.settext(n);
        }
        String s;
        ScriptServer server = ScriptRuntime.server();
        if (!ScriptRuntime.enabled())
            s = L10n.get("scripting.status.off");
        else if (server != null)
            s = L10n.get("scripting.status.running", String.valueOf(server.port()));
        else
            s = L10n.get("scripting.status.stopped");
        if (!s.equals(status.text()))
            status.settext(s);
        String d = L10n.get("scripting.dir.resolved", folder().getAbsolutePath());
        if (!d.equals(dirResolved.text()))
            dirResolved.settext(d);
    }

    /** The folder the entry names (as it would be after Save), without creating it. */
    private File folder() {
        String d = dir.text().trim();
        return d.isEmpty() ? new File("scripts") : new File(d);
    }

    /** Applies the typed interpreter path and looks for Python on a helper thread; the lookup starts processes. */
    private void testPython() {
        NConfig.set(NConfig.Key.scriptingPython, python.text().trim());
        NConfig.needUpdate();
        testResult.settext(L10n.get("scripting.test.running"));
        Thread t = new Thread(() -> {
            try {
                List<String> found = PythonFinder.find();
                pendingTest = (found == null) ? L10n.get("scripting.test.notfound") : L10n.get("scripting.test.found", String.join(" ", found));
            } catch (InterruptedException e) {
                // Not a bot thread: nobody stops it but the JVM shutting down.
                Thread.currentThread().interrupt();
            }
        }, "Scripting-PythonTest");
        t.setDaemon(true);
        t.start();
    }

    private void openFolder() {
        File d = folder();
        if (!d.isDirectory() && !d.mkdirs()) {
            note.settext(L10n.get("scripting.open_failed", d.getAbsolutePath()));
            return;
        }
        // Desktop calls can block for a while on some systems; keep them off the UI thread.
        Thread t = new Thread(() -> {
            try {
                if (!java.awt.Desktop.isDesktopSupported() || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                    pendingNote = L10n.get("scripting.open_failed", d.getAbsolutePath());
                    return;
                }
                java.awt.Desktop.getDesktop().open(d.getAbsoluteFile());
            } catch (IOException | UnsupportedOperationException | SecurityException | IllegalArgumentException e) {
                pendingNote = L10n.get("scripting.open_failed", d.getAbsolutePath());
            }
        }, "Scripting-OpenFolder");
        t.setDaemon(true);
        t.start();
    }

    private void rescan() {
        List<ScriptCatalog.Entry> found = ScriptCatalog.refresh();
        syncMenus();
        note.settext(L10n.get("scripting.rescanned", String.valueOf(found.size())));
    }

    private void openDocs() {
        try {
            if (ui == null || ui.wnd == null)
                throw new IOException("no window");
            ui.wnd.toolkit().browse(java.net.URI.create(DOCS_URL));
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            note.settext(L10n.get("scripting.open_failed", DOCS_URL));
        }
    }

    /** Every session's bot menu re-reads the Scripts folder (and shows or hides it), on that session's own thread. */
    private static void syncMenus() {
        for (SessionContext sc : SessionManager.getInstance().getAllSessions()) {
            if (sc.ui == null)
                continue;
            ScriptRuntime.onUiThread(sc.ui, () -> {
                NGameUI gui = sc.ui.gui;
                if (gui != null && gui.botsMenu != null)
                    gui.botsMenu.syncScripts();
            });
        }
    }

    private void showWarning() {
        if (warning != null && warning.parent != null) {
            warning.raise();
            return;
        }
        warning = new Warning();
        Widget host = (ui != null && ui.gui != null) ? ui.gui : (ui != null ? ui.root : null);
        if (host == null)
            return;
        host.add(warning, new Coord(UI.scale(160), UI.scale(140)));
    }

    private void accepted() {
        NConfig.set(NConfig.Key.scriptingWarned, true);
        NConfig.needUpdate();
        enable.set(true);
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static Integer parsePort(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            return (p >= 0 && p <= 65535) ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Shown once, the first time the player ticks "Enable scripting". Only the accept button turns it on. */
    private class Warning extends Window {
        Warning() {
            super(UI.scale(new Coord(420, 60)), L10n.get("scripting.warn.title"));
            int margin = UI.scale(10);
            int y = margin;
            y = line(L10n.get("scripting.warn.line1"), Color.WHITE, margin, y);
            y = line(L10n.get("scripting.warn.line2"), Color.WHITE, margin, y);
            y = line(L10n.get("scripting.warn.line3"), Color.ORANGE, margin, y);
            y += UI.scale(6);
            Button yes = add(new Button(UI.scale(140), L10n.get("scripting.warn.accept")) {
                @Override
                public void click() {
                    super.click();
                    Warning.this.destroy();
                    accepted();
                }
            }, new Coord(margin, y));
            add(new Button(UI.scale(100), L10n.get("scripting.warn.cancel")) {
                @Override
                public void click() {
                    super.click();
                    Warning.this.destroy();
                }
            }, new Coord(margin + yes.sz.x + UI.scale(10), y));
            pack();
        }

        private int line(String text, Color color, int x, int y) {
            Label l = add(new Label(text), new Coord(x, y));
            l.setcolor(color);
            return y + l.sz.y + UI.scale(3);
        }

        @Override
        public void wdgmsg(Widget sender, String msg, Object... args) {
            if (sender == this && msg.equals("close")) {
                destroy();
                return;
            }
            super.wdgmsg(sender, msg, args);
        }
    }
}
