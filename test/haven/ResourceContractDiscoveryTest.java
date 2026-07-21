package haven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceContractDiscoveryTest {
    @FromResource(name = "fixture/a", version = 3)
    static class AnnotatedA {
    }

    @FromResource(name = "fixture/z", version = 7)
    static class AnnotatedZ {
    }

    static class Plain {
    }

    @Test
    void discovers_annotated_classes_in_path_order(@TempDir Path root) throws IOException {
        copyClass(root, AnnotatedZ.class);
        copyClass(root, Plain.class);
        copyClass(root, AnnotatedA.class);
        Files.write(root.resolve("ignored.txt"), new byte[] {0});
        Files.createDirectories(root.resolve("metadata"));
        Files.write(root.resolve("metadata/package-info.class"), new byte[] {0});
        Files.write(root.resolve("module-info.class"), new byte[] {0});

        List<ResourceContractVerifier.Declaration> declarations =
                ResourceContractDiscovery.discover(root, getClass().getClassLoader());

        assertEquals(2, declarations.size());
        assertEquals(AnnotatedA.class.getName(), declarations.get(0).className);
        assertEquals("fixture/a", declarations.get(0).resourceName);
        assertEquals(3, declarations.get(0).version);
        assertEquals(AnnotatedZ.class.getName(), declarations.get(1).className);
        assertEquals("fixture/z", declarations.get(1).resourceName);
        assertEquals(7, declarations.get(1).version);
    }

    @Test
    void rejects_missing_class_directory(@TempDir Path root) {
        Path missing = root.resolve("missing");

        IOException error = assertThrows(IOException.class,
                () -> ResourceContractDiscovery.discover(missing, getClass().getClassLoader()));

        assertTrue(error.getMessage().contains(missing.toString()));
    }

    @Test
    void reports_classes_missing_from_the_loader(@TempDir Path root) throws IOException {
        Path missing = root.resolve("haven/Missing.class");
        Files.createDirectories(missing.getParent());
        Files.write(missing, new byte[] {0});

        IOException error = assertThrows(IOException.class,
                () -> ResourceContractDiscovery.discover(root, getClass().getClassLoader()));

        assertTrue(error.getMessage().contains("haven.Missing"));
        assertInstanceOf(ClassNotFoundException.class, error.getCause());
    }

    @Test
    void reports_linkage_errors(@TempDir Path root) throws IOException {
        Path broken = root.resolve("haven/Broken.class");
        Files.createDirectories(broken.getParent());
        Files.write(broken, new byte[] {0});
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if(name.equals("haven.Broken"))
                    throw new NoClassDefFoundError("broken fixture");
                return super.loadClass(name, resolve);
            }
        };

        IOException error = assertThrows(IOException.class,
                () -> ResourceContractDiscovery.discover(root, loader));

        assertTrue(error.getMessage().contains("haven.Broken"));
        assertInstanceOf(NoClassDefFoundError.class, error.getCause());
    }

    @Test
    void requires_root_and_loader(@TempDir Path root) {
        assertThrows(NullPointerException.class,
                () -> ResourceContractDiscovery.discover(null, getClass().getClassLoader()));
        assertThrows(NullPointerException.class,
                () -> ResourceContractDiscovery.discover(root, null));
    }

    private static void copyClass(Path root, Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        Path target = root.resolve(resource.replace('/', java.io.File.separatorChar));
        Files.createDirectories(target.getParent());
        try(InputStream input = type.getClassLoader().getResourceAsStream(resource)) {
            if(input == null)
                throw new IOException("compiled fixture not found: " + resource);
            Files.copy(input, target);
        }
    }
}
