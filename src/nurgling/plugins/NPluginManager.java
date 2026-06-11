package nurgling.plugins;

import nurgling.NConfig;
import nurgling.NGameUI;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads external plugins from a drop-folder ({@code plugins/} by default).
 *
 * By default only plugins signed by the client's embedded trusted certificate
 * are loaded, so users are not tricked into running untrusted or malicious jars.
 * A dev escape hatch ({@link NConfig.Key#pluginsAllowUnsigned}) disables
 * verification for local testing.
 *
 * The trusted certificate is read from the classpath resource
 * {@code /nurgling/plugins/trusted.cer} (a non-.java file under src/, copied
 * into the build by the client's build script).
 */
public class NPluginManager {

    private static final List<NPlugin> plugins = new ArrayList<>();
    private static boolean loaded = false;
    private static X509Certificate trusted = null;

    /** Discover and load all plugin jars. Idempotent. */
    public static synchronized void loadAll() {
        if (loaded) return;
        loaded = true;

        trusted = loadTrustedCert();
        boolean allowUnsigned = isAllowUnsigned();

        File dir = pluginsDir();
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] jars = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
        if (jars == null) return;

        for (File jar : jars) {
            try {
                NPlugin p = loadPlugin(jar, allowUnsigned);
                if (p != null) {
                    plugins.add(p);
                    System.out.println("[Plugins] Loaded: " + p.name() + " (" + jar.getName() + ")");
                }
            } catch (Exception e) {
                System.out.println("[Plugins] Failed to load " + jar.getName() + ": " + e);
            }
        }
    }

    /** Called when a session's NGameUI is ready; notifies every loaded plugin. */
    public static synchronized void onGameUIReady(NGameUI gui) {
        loadAll();
        for (NPlugin p : plugins) {
            try {
                p.onLoad(gui);
            } catch (RuntimeException e) {
                System.out.println("[Plugins] onLoad error in " + p.name() + ": " + e);
            }
        }
    }

    private static NPlugin loadPlugin(File jar, boolean allowUnsigned) throws Exception {
        if (!allowUnsigned) {
            if (trusted == null) {
                System.out.println("[Plugins] Refusing " + jar.getName()
                        + ": no trusted certificate embedded. Sign the plugin, or set pluginsAllowUnsigned for dev.");
                return null;
            }
            if (!isSignedByTrusted(jar)) {
                System.out.println("[Plugins] Refusing " + jar.getName() + ": not signed by the trusted key.");
                return null;
            }
        } else {
            System.out.println("[Plugins] WARNING: signature verification disabled (dev mode) for " + jar.getName());
        }

        String entry = readEntryClass(jar);
        if (entry == null) {
            System.out.println("[Plugins] No entry class (plugin.properties 'main=') in " + jar.getName());
            return null;
        }

        URLClassLoader cl = new URLClassLoader(
                new URL[]{jar.toURI().toURL()},
                NPluginManager.class.getClassLoader());
        Class<?> cls = Class.forName(entry, true, cl);
        Object o = cls.getDeclaredConstructor().newInstance();
        if (!(o instanceof NPlugin)) {
            System.out.println("[Plugins] Entry class is not an NPlugin: " + entry);
            cl.close();
            return null;
        }
        return (NPlugin) o;
    }

    private static String readEntryClass(File jar) throws Exception {
        try (JarFile jf = new JarFile(jar)) {
            JarEntry pe = jf.getJarEntry("plugin.properties");
            if (pe == null) return null;
            Properties props = new Properties();
            try (InputStream in = jf.getInputStream(pe)) {
                props.load(in);
            }
            String main = props.getProperty("main");
            return (main != null && !main.trim().isEmpty()) ? main.trim() : null;
        }
    }

    /** True only if every content entry is signed by the trusted certificate. */
    private static boolean isSignedByTrusted(File jar) {
        try (JarFile jf = new JarFile(jar, true)) {
            byte[] buf = new byte[8192];
            boolean anyContent = false;
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry je = en.nextElement();
                // Reading the entry fully is required before certificates populate.
                try (InputStream in = jf.getInputStream(je)) {
                    while (in.read(buf) != -1) { /* discard */ }
                }
                if (je.isDirectory()) continue;
                String nm = je.getName();
                if (nm.startsWith("META-INF/")) continue;
                Certificate[] certs = je.getCertificates();
                if (certs == null || certs.length == 0) {
                    return false; // an unsigned content entry
                }
                boolean match = false;
                for (Certificate c : certs) {
                    if (c.equals(trusted)) { match = true; break; }
                }
                if (!match) return false;
                anyContent = true;
            }
            return anyContent;
        } catch (Exception e) {
            System.out.println("[Plugins] Signature check failed for " + jar.getName() + ": " + e);
            return false;
        }
    }

    private static X509Certificate loadTrustedCert() {
        try (InputStream in = NPluginManager.class.getResourceAsStream("/nurgling/plugins/trusted.cer")) {
            if (in == null) return null;
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static File pluginsDir() {
        try {
            Object v = NConfig.get(NConfig.Key.pluginsDir);
            if (v instanceof String && !((String) v).isEmpty()) {
                return new File((String) v);
            }
        } catch (Exception ignored) {
        }
        return new File("plugins");
    }

    private static boolean isAllowUnsigned() {
        try {
            return Boolean.TRUE.equals(NConfig.get(NConfig.Key.pluginsAllowUnsigned));
        } catch (Exception e) {
            return false;
        }
    }
}
