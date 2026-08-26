package nurgling.db.setup;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Just enough Docker to stand a PostgreSQL container up and see whether it worked.
 *
 * <p>Everything here blocks on an external process and must be called off the UI thread.
 */
public class DockerProbe {

    /** What one command did. */
    public static class Run {
        public final int exit;
        public final String output;
        public final boolean timedOut;

        Run(int exit, String output, boolean timedOut) {
            this.exit = exit;
            this.output = output;
            this.timedOut = timedOut;
        }

        public boolean ok() {
            return exit == 0 && !timedOut;
        }
    }

    /** Whether this machine can host, and what to say if it cannot. */
    public static class Availability {
        public boolean cliPresent;
        public boolean daemonRunning;
        public boolean composePresent;
        public String engineVersion = "";
        public String composeVersion = "";
        public String problem = "";

        public boolean usable() {
            return cliPresent && daemonRunning && composePresent;
        }
    }

    public static Availability detect() throws InterruptedException {
        Availability a = new Availability();

        Run cli = run(null, 10, "docker", "--version");
        a.cliPresent = cli.ok();
        if (!a.cliPresent) {
            a.problem = "Docker is not installed on this machine, or is not on the PATH.";
            return a;
        }

        /* Asking for the SERVER version on purpose. The CLI answers --version perfectly happily
         * while the engine is stopped, which on Windows is the single most common state for a
         * machine that "has Docker": installed, never started, or waiting on virtualisation being
         * enabled in the BIOS. */
        Run server = run(null, 20, "docker", "version", "--format", "{{.Server.Version}}");
        a.daemonRunning = server.ok() && !server.output.trim().isEmpty();
        a.engineVersion = server.output.trim();
        if (!a.daemonRunning) {
            a.problem = "Docker is installed but not running. Start Docker Desktop and try again.";
            return a;
        }

        Run compose = run(null, 15, "docker", "compose", "version", "--short");
        a.composePresent = compose.ok();
        a.composeVersion = compose.output.trim();
        if (!a.composePresent) {
            a.problem = "This Docker has no 'docker compose' command. Update Docker and try again.";
        }
        return a;
    }

    /** Run {@code docker compose ...} in a directory. */
    public static Run compose(Path dir, int timeoutSeconds, String... args)
            throws InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("compose");
        for (String a : args)
            cmd.add(a);
        return run(dir, timeoutSeconds, cmd.toArray(new String[0]));
    }

    /**
     * Whether the container reports itself healthy.
     *
     * <p>The compose file defines a healthcheck precisely so this question has an answer: without
     * one, "still starting up" and "crashed on boot" look identical from outside, and the setup
     * flow would have to guess with a sleep.
     */
    public static boolean healthy(Path dir) throws InterruptedException {
        Run r = compose(dir, 15, "ps", "--format", "{{.Health}}");
        if (!r.ok())
            return false;
        for (String line : r.output.split("\\R")) {
            if (line.trim().equalsIgnoreCase("healthy"))
                return true;
        }
        return false;
    }

    public static Run run(Path dir, int timeoutSeconds, String... command)
            throws InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (dir != null)
            pb.directory(dir.toFile());
        pb.redirectErrorStream(true);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            return new Run(-1, String.valueOf(e.getMessage()), false);
        }

        StringBuilder sb = new StringBuilder();
        /* Drained on a helper thread: a process whose output fills the pipe buffer blocks forever
         * waiting for someone to read it, and would then hit the timeout looking like a hang. */
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (sb) {
                        sb.append(line).append('\n');
                    }
                }
            } catch (IOException ignore) {
                // Process ended; whatever was read is what we report.
            }
        }, "docker-out");
        reader.setDaemon(true);
        reader.start();

        boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            reader.join(1000);
            synchronized (sb) {
                return new Run(-1, sb.toString(), true);
            }
        }
        reader.join(2000);
        synchronized (sb) {
            return new Run(proc.exitValue(), sb.toString(), false);
        }
    }
}
