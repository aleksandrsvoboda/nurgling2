package nurgling.widgets.db;

import haven.*;
import haven.Button;
import haven.Label;
import nurgling.NConfig;
import nurgling.NCore;
import nurgling.db.DbCredentials;
import nurgling.db.DbSettings;
import nurgling.db.setup.ComposeTemplate;
import nurgling.db.setup.DockerProbe;
import nurgling.db.service.VillagerService;

import java.awt.Color;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;

/**
 * Stands up a village database, on this machine or on another one.
 *
 * <p>Both paths produce the identical container from the identical template - they differ only in
 * who runs it. That is possible because the container's entire job is an empty PostgreSQL with one
 * admin role: the schema, the grants and every future migration belong to the client, so there is
 * nothing here that has to be kept in step with a Nurgling version.
 */
public class HostWizard extends Window {
    private static final Coord WINDOW_SIZE = UI.scale(new Coord(600, 430));

    private static final int HERE = 0;
    private static final int REMOTE = 1;

    private final Label dockerStatus;
    private final Label progress;
    private final Label reachability;
    private final TextEntry villageEntry;
    private final TextEntry portEntry;
    private final TextEntry addressEntry;

    private final Button checkButton;
    private final Button createButton;
    private final Button copyBlockButton;
    private final Button saveScriptButton;
    private final Button useAddressButton;
    private final Button tlsButton;
    private final Label remoteHint;
    private final Label addressLabel;

    private int placement = HERE;
    private boolean built = false;

    /** Generated once per wizard, so the pasted block and the saved settings agree. */
    private final String password = VillagerService.generatePassword();

    private volatile String pendingProgress = null;
    private volatile Color pendingProgressColor = Color.WHITE;
    private volatile String pendingDocker = null;
    private volatile Color pendingDockerColor = Color.WHITE;
    private volatile boolean dockerUsable = false;
    private volatile boolean busy = false;

    public HostWizard() {
        super(WINDOW_SIZE, "Host a village database");
        int y = UI.scale(5);

        add(new Label("Where should the database run?"), new Coord(UI.scale(5), y));
        y += UI.scale(20);

        RadioGroup where = new RadioGroup(this) {
            public void changed(int btn, String lbl) {
                placement = btn;
                updateVisibility();
            }
        };
        where.add("On this PC", new Coord(UI.scale(20), y));
        y += UI.scale(20);
        where.add("On another machine (server, VPS, spare box)", new Coord(UI.scale(20), y));
        y += UI.scale(26);

        add(new Label("Village name:"), new Coord(UI.scale(5), y + UI.scale(3)));
        villageEntry = add(new TextEntry(UI.scale(180), ""), new Coord(UI.scale(95), y));
        add(new Label("Port:"), new Coord(UI.scale(300), y + UI.scale(3)));
        portEntry = add(new TextEntry(UI.scale(70), String.valueOf(DbSettings.DEFAULT_PORT)),
                        new Coord(UI.scale(340), y));
        y += villageEntry.sz.y + UI.scale(10);

        dockerStatus = add(new Label("Checking for Docker..."), new Coord(UI.scale(5), y));
        y += UI.scale(20);

        checkButton = add(new Button(UI.scale(110), "Check again") {
            public void click() {
                super.click();
                checkDocker();
            }
        }, new Coord(UI.scale(5), y));
        createButton = add(new Button(UI.scale(170), "Create the database") {
            public void click() {
                super.click();
                createHere();
            }
        }, new Coord(UI.scale(125), y));

        copyBlockButton = add(new Button(UI.scale(190), "Copy setup command") {
            public void click() {
                super.click();
                copySetupBlock();
            }
        }, new Coord(UI.scale(5), y));
        copyBlockButton.tooltip = Text.render(
            "Copies one self-contained block. Paste it into a shell on that machine - it downloads "
          + "nothing and every line is readable before you run it.").tex();
        saveScriptButton = add(new Button(UI.scale(150), "Save as script...") {
            public void click() {
                super.click();
                saveSetupScript();
            }
        }, new Coord(UI.scale(205), y));
        y += createButton.sz.y + UI.scale(10);

        remoteHint = add(new Label(""), new Coord(UI.scale(5), y));
        y += UI.scale(18);

        addressLabel = add(new Label("Address players will use:"), new Coord(UI.scale(5), y + UI.scale(3)));
        addressEntry = add(new TextEntry(UI.scale(200), ""), new Coord(UI.scale(165), y));
        useAddressButton = add(new Button(UI.scale(150), "Connect to it") {
            public void click() {
                super.click();
                connectRemote();
            }
        }, new Coord(UI.scale(375), y));
        y += addressEntry.sz.y + UI.scale(12);

        progress = add(new Label(""), new Coord(UI.scale(5), y));
        y += UI.scale(20);
        reachability = add(new Label(""), new Coord(UI.scale(5), y));
        y += UI.scale(20);

        tlsButton = add(new Button(UI.scale(230), "Copy the encryption step") {
            public void click() {
                super.click();
                if (DbClipboard.copy(ComposeTemplate.tlsBlock()))
                    setProgress("Encryption commands copied. Run them on the database machine, then "
                              + "set Encryption to Required.", Color.YELLOW);
            }
        }, new Coord(UI.scale(5), y));
        tlsButton.tooltip = Text.render(
            "Without this the connection is unencrypted, and the database holds every villager's "
          + "hearth secret.").tex();

        built = true;
        updateVisibility();
        showLanAddress();
        checkDocker();
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

    // ---- docker --------------------------------------------------------------------------

    private void checkDocker() {
        if (busy)
            return;
        pendingDocker = "Checking for Docker...";
        pendingDockerColor = Color.WHITE;
        worker("docker-check", () -> {
            DockerProbe.Availability a = DockerProbe.detect();
            dockerUsable = a.usable();
            if (a.usable()) {
                pendingDocker = "Docker " + a.engineVersion + " is running.";
                pendingDockerColor = Color.GREEN;
            } else {
                pendingDocker = a.problem;
                pendingDockerColor = Color.ORANGE;
            }
        });
    }

    private void createHere() {
        if (busy)
            return;
        if (!dockerUsable) {
            setProgress("Docker is not usable on this machine yet - see above, or host it on "
                      + "another machine instead.", Color.ORANGE);
            return;
        }
        final int port = port();
        final String village = villageEntry.text().trim();

        worker("db-setup", () -> {
            Path dir = Paths.get(nurgling.NUtils.getDataFile(ComposeTemplate.DIRECTORY));
            setProgress("Writing " + dir + "...", Color.WHITE);
            Files.createDirectories(dir);
            Files.write(dir.resolve(".env"),
                ComposeTemplate.env(ComposeTemplate.ADMIN_ROLE, password,
                                    DbSettings.DEFAULT_DATABASE, port).getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("docker-compose.yml"),
                ComposeTemplate.compose().getBytes(StandardCharsets.UTF_8));

            setProgress("Starting the container...", Color.WHITE);
            DockerProbe.Run up = DockerProbe.compose(dir, 180, "up", "-d");
            if (!up.ok()) {
                setProgress("Docker could not start it: " + firstLine(up.output), Color.ORANGE);
                return;
            }

            /* Poll the healthcheck rather than sleeping: a first start has to initialise the data
             * directory, which takes a variable few seconds, and a container that died on boot must
             * not be reported as a working village. */
            setProgress("Waiting for PostgreSQL to come up...", Color.WHITE);
            boolean healthy = false;
            for (int i = 0; i < 30 && !healthy; i++) {
                healthy = DockerProbe.healthy(dir);
                if (!healthy)
                    Thread.sleep(2000);
            }
            if (!healthy) {
                setProgress("The container started but never became healthy. Run "
                          + "'docker compose logs' in " + dir, Color.ORANGE);
                return;
            }

            applySettings(village, "127.0.0.1", port);
            setProgress("Done. The database is running and this client is connected.", Color.GREEN);
        });
    }

    // ---- remote --------------------------------------------------------------------------

    private String setupBlock() {
        return ComposeTemplate.remoteBlock(ComposeTemplate.ADMIN_ROLE, password,
                                           DbSettings.DEFAULT_DATABASE, port());
    }

    private void copySetupBlock() {
        if (DbClipboard.copy(setupBlock())) {
            setProgress("Copied. Paste it into a shell on that machine, then put its address below.",
                        Color.GREEN);
        } else {
            setProgress("Could not reach the clipboard - use 'Save as script...' instead.",
                        Color.ORANGE);
        }
    }

    private void saveSetupScript() {
        final String block = setupBlock();
        java.awt.EventQueue.invokeLater(() -> {
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
            fc.setSelectedFile(new java.io.File("nurgling-db-setup.sh"));
            if (fc.showSaveDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION)
                return;
            try {
                Files.write(fc.getSelectedFile().toPath(),
                    ("#!/bin/sh\nset -e\n" + block).getBytes(StandardCharsets.UTF_8));
                setProgress("Saved. Copy it to that machine and run it with sh.", Color.GREEN);
            } catch (IOException e) {
                setProgress("Could not save: " + e.getMessage(), Color.ORANGE);
            }
        });
    }

    private void connectRemote() {
        String address = addressEntry.text().trim();
        if (address.isEmpty()) {
            setProgress("Put in the address players will use to reach that machine.", Color.ORANGE);
            return;
        }
        DbSettings.HostPort hp = DbSettings.parseNode(address);
        int p = address.contains(":") ? hp.port : port();
        applySettings(villageEntry.text().trim(), hp.host, p);
        setProgress("Settings saved. Watch the status line in Database settings.", Color.GREEN);
    }

    // ---- shared --------------------------------------------------------------------------

    /**
     * Point this client at the database that was just created, and connect.
     *
     * <p>Writing the password through {@link DbCredentials} rather than the config is the point of
     * that class: it never touches the file people share when reporting a problem.
     */
    private void applySettings(String village, String host, int port) {
        NConfig.set(NConfig.Key.ndbenable, true);
        NConfig.set(NConfig.Key.postgres, true);
        NConfig.set(NConfig.Key.sqlite, false);
        NConfig.set(NConfig.Key.dbHost, host);
        NConfig.set(NConfig.Key.dbPort, port);
        NConfig.set(NConfig.Key.dbName, DbSettings.DEFAULT_DATABASE);
        NConfig.set(NConfig.Key.dbSsl, DbSettings.SSL_PREFER);
        NConfig.set(NConfig.Key.dbVillage, village);
        NConfig.set(NConfig.Key.serverUser, ComposeTemplate.ADMIN_ROLE);
        NConfig.set(NConfig.Key.serverNode, host + ":" + port);
        DbCredentials.store(password);
        NConfig.needUpdate();

        if (NCore.databaseManager != null)
            NCore.databaseManager.reconnect();
    }

    private int port() {
        try {
            int v = Integer.parseInt(portEntry.text().trim());
            return (v > 0 && v <= 65535) ? v : DbSettings.DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return DbSettings.DEFAULT_PORT;
        }
    }

    /**
     * Show the LAN address, which is the whole answer for a village that plays in one house.
     *
     * <p>Nothing here tries to prove the port is reachable from the internet - the client cannot
     * see itself from outside, and guessing would be worse than saying so.
     */
    private void showLanAddress() {
        String lan = lanAddress();
        reachability.settext(lan.isEmpty()
            ? "Same network: use this PC's local address. Over the internet: forward the port."
            : "On this network: " + lan + ". Over the internet: forward the port on your router, "
              + "then have your first villager press Test connection.");
        reachability.setcolor(Color.LIGHT_GRAY);
    }

    private static String lanAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual())
                    continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isSiteLocalAddress() && a.getAddress().length == 4)
                        return a.getHostAddress();
                }
            }
        } catch (java.net.SocketException e) {
            // No usable interface list; the generic hint covers it.
        }
        return "";
    }

    private void updateVisibility() {
        if (!built)
            return;
        boolean here = (placement == HERE);
        dockerStatus.visible = here;
        checkButton.visible = here;
        createButton.visible = here;

        copyBlockButton.visible = !here;
        saveScriptButton.visible = !here;
        remoteHint.visible = !here;
        addressLabel.visible = !here;
        addressEntry.visible = !here;
        useAddressButton.visible = !here;

        if (!here) {
            remoteHint.settext("Paste that into a shell on the other machine. It needs Docker and "
                             + "nothing else.");
            remoteHint.setcolor(Color.LIGHT_GRAY);
        }
    }

    private void setProgress(String text, Color color) {
        pendingProgress = text;
        pendingProgressColor = color;
    }

    /**
     * Run one blocking step off the UI thread.
     *
     * <p>{@link InterruptedException} is never swallowed: the thread re-flags itself and stops, so a
     * shutdown still tears this down promptly.
     */
    private void worker(String name, Step step) {
        busy = true;
        Thread t = new Thread(() -> {
            try {
                step.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                setProgress("Failed: " + e.getMessage(), Color.ORANGE);
            } finally {
                busy = false;
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    private interface Step {
        void run() throws InterruptedException, IOException;
    }

    private static String firstLine(String s) {
        if (s == null)
            return "";
        int nl = s.indexOf('\n');
        String line = (nl < 0) ? s : s.substring(0, nl);
        return line.length() > 90 ? line.substring(0, 90) + "..." : line;
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        String p = pendingProgress;
        if (p != null) {
            progress.settext(p);
            progress.setcolor(pendingProgressColor);
            pendingProgress = null;
        }
        String d = pendingDocker;
        if (d != null) {
            dockerStatus.settext(d);
            dockerStatus.setcolor(pendingDockerColor);
            pendingDocker = null;
        }
        createButton.disable(busy);
        checkButton.disable(busy);
    }
}
