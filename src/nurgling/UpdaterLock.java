package nurgling;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Tells the Nurgling updater that this client is running. Every client holds a shared
 * lock on updater/client.lock for the life of the JVM; the updater asks for an
 * exclusive one and waits with its update while any client holds it. The OS drops
 * the lock when the process exits, so it can never go stale.
 */
public final class UpdaterLock {
    /* Kept reachable for the JVM's lifetime; a collected channel would release the lock. */
    private static FileChannel channel;
    private static FileLock lock;

    private UpdaterLock() {}

    public static void hold() {
        Path dir = installDir().resolve("updater");
        if (!Files.isDirectory(dir))
            return; // not installed by the updater (e.g. running from an IDE)
        try {
            channel = FileChannel.open(dir.resolve("client.lock"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            lock = channel.lock(0, Long.MAX_VALUE, true);
        } catch (IOException | OverlappingFileLockException e) {
            System.out.println("[UpdaterLock] could not take updater/client.lock: " + e);
        }
    }

    private static Path installDir() {
        try {
            Path jar = Paths.get(UpdaterLock.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(jar) ? jar.getParent() : Paths.get("");
        } catch (URISyntaxException | SecurityException e) {
            return Paths.get("");
        }
    }
}
