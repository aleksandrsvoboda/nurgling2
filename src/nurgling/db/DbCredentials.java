package nurgling.db;

import nurgling.NConfig;
import nurgling.NUtils;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * Where the database password lives.
 *
 * <p>It used to sit in {@code nconfig.nurgling.json} in the clear - the same file people attach to
 * bug reports - next to hundreds of unrelated settings, on an account that until now was usually a
 * superuser. Keeping it in its own file means it is not shared by accident.
 *
 * <p>The stored form is <em>obfuscated, not encrypted</em>, and the UI says so. A client that has to
 * replay a password to a server cannot also keep it secret from whoever controls the machine; the
 * value here is that it no longer travels with everything else.
 */
public class DbCredentials {
    private static final String FILE = "dbcreds.nurgling.json";
    private static final byte[] MASK = "nurgling-db-credential-store".getBytes(StandardCharsets.UTF_8);

    private static volatile String cached = null;
    private static volatile boolean loaded = false;

    /**
     * The stored password.
     *
     * <p>Adopts, once, whatever the old config key still holds, so an existing village keeps
     * connecting without anybody retyping anything - and clears that key afterwards, which is the
     * whole point of the move.
     */
    public static synchronized String password() {
        if (!loaded) {
            cached = read();
            if (cached == null) {
                Object legacy = NConfig.get(NConfig.Key.serverPass);
                String s = (legacy == null) ? "" : legacy.toString();
                if (!s.isEmpty()) {
                    cached = s;
                    /* Only drop the old copy once the new one is definitely on disk. Clearing first
                     * and failing to write would destroy the only record of the password. */
                    if (write(s)) {
                        NConfig.set(NConfig.Key.serverPass, "");
                        NConfig.needUpdate();
                        System.out.println("[DbCredentials] moved the database password out of nconfig.nurgling.json");
                    } else {
                        System.err.println("[DbCredentials] keeping the password in nconfig for now: "
                            + "could not write " + FILE);
                    }
                }
            }
            loaded = true;
        }
        return cached == null ? "" : cached;
    }

    public static synchronized void store(String password) {
        cached = (password == null) ? "" : password;
        loaded = true;
        if (!write(cached))
            return;
        /* Belt and braces: an older client may have left a copy behind, and leaving it there would
         * make this whole exercise pointless. */
        Object legacy = NConfig.get(NConfig.Key.serverPass);
        if (legacy != null && !legacy.toString().isEmpty()) {
            NConfig.set(NConfig.Key.serverPass, "");
            NConfig.needUpdate();
        }
    }

    private static Path path() {
        return Paths.get(NUtils.getDataFile(FILE));
    }

    private static String read() {
        Path p = path();
        try {
            if (!Files.isRegularFile(p))
                return null;
            JSONObject o = new JSONObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            String stored = o.optString("password", "");
            return stored.isEmpty() ? "" : deobfuscate(stored);
        } catch (IOException | org.json.JSONException | IllegalArgumentException e) {
            System.err.println("[DbCredentials] could not read " + FILE + ": " + e.getMessage());
            return null;
        }
    }

    private static boolean write(String password) {
        JSONObject o = new JSONObject();
        o.put("password", (password == null || password.isEmpty()) ? "" : obfuscate(password));
        o.put("note", "Obfuscated, not encrypted. Treat this file as a password.");
        try {
            Files.write(path(), o.toString(2).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            System.err.println("[DbCredentials] could not write " + FILE + ": " + e.getMessage());
            return false;
        }
    }

    private static String obfuscate(String s) {
        return Base64.getEncoder().encodeToString(xor(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static String deobfuscate(String s) {
        return new String(xor(Base64.getDecoder().decode(s)), StandardCharsets.UTF_8);
    }

    private static byte[] xor(byte[] in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++)
            out[i] = (byte) (in[i] ^ MASK[i % MASK.length]);
        return out;
    }
}
