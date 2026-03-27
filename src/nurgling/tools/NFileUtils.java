package nurgling.tools;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Atomic file write utilities to prevent corruption on crash/kill.
 *
 * Instead of truncating the target file and then writing (which leaves
 * an empty or partial file if the process dies mid-write), this writes
 * to a .tmp file first, then renames it over the target. A .bak copy
 * of the previous version is kept for recovery.
 */
public class NFileUtils {

    /**
     * Writes content to a file atomically using a temp-file-then-rename pattern.
     * Keeps a .bak backup of the previous version.
     *
     * @param targetPath path to the target file
     * @param content    the full content to write
     */
    public static void writeAtomically(String targetPath, String content) throws IOException {
        Path target = Path.of(targetPath);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Path backup = target.resolveSibling(target.getFileName() + ".bak");

        // Step 1: Write to temp file (original untouched)
        Files.write(temp, content.getBytes(StandardCharsets.UTF_8));

        // Step 2: Backup current file if it exists
        if (Files.exists(target)) {
            try {
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // Backup failure is non-fatal -- proceed with the save
                System.err.println("[NFileUtils] Warning: could not create backup for " + targetPath + ": " + e.getMessage());
            }
        }

        // Step 3: Atomic rename temp -> target
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Fallback for filesystems that don't support atomic move
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Reads a file's content as a string. If the file is missing, empty, or
     * contains invalid content (doesn't start with '{' or '['), attempts to
     * read from the .bak file instead.
     *
     * @param targetPath path to the target file
     * @return the file content, or null if neither file nor backup is usable
     */
    public static String readWithBackupFallback(String targetPath) {
        Path target = Path.of(targetPath);
        Path backup = target.resolveSibling(target.getFileName() + ".bak");

        // Try primary file
        String content = readFileContent(target);
        if (isValidJsonContent(content)) {
            return content;
        }

        // Primary is bad -- try backup
        if (Files.exists(backup)) {
            System.err.println("[NFileUtils] Primary file corrupt or empty, trying backup: " + targetPath);
            String backupContent = readFileContent(backup);
            if (isValidJsonContent(backupContent)) {
                // Restore backup as primary so next save has a good base
                try {
                    Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
                    System.err.println("[NFileUtils] Restored from backup: " + targetPath);
                } catch (IOException e) {
                    System.err.println("[NFileUtils] Warning: could not restore backup to primary: " + e.getMessage());
                }
                return backupContent;
            }
        }

        // Both are bad
        if (content != null && !content.isEmpty()) {
            System.err.println("[NFileUtils] Both primary and backup are corrupt: " + targetPath);
        }
        return null;
    }

    private static String readFileContent(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isValidJsonContent(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String trimmed = content.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }
}
