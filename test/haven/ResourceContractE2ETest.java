package haven;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceContractE2ETest {
    private static final String DEFAULT_ENDPOINT = "https://game.havenandhearth.com/res/";

    @Test
    @Tag("resource-e2e")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void compiled_resource_contracts_resolve_against_live_endpoint() throws IOException {
        Path classes = Paths.get(System.getProperty("resource.contract.classes", "build/classes"));
        URI endpoint = URI.create(System.getProperty("resource.contract.url", DEFAULT_ENDPOINT));
        List<ResourceContractVerifier.Declaration> declarations = discover(classes);
        Resource.Pool resources = new Resource.Pool(new Resource.HttpSource(endpoint));

        ResourceContractVerifier.Report report = ResourceContractVerifier.verify(
                declarations,
                (name, version) -> resources.loadwait(name, version).ver);

        System.out.println(report.describe());
        assertTrue(report.passed(), report.describe());
    }

    private static List<ResourceContractVerifier.Declaration> discover(Path root) throws IOException {
        if(!Files.isDirectory(root))
            throw new IOException("compiled classes directory not found: " + root);

        List<Path> files = new ArrayList<>();
        try(Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(files::add);
        }

        List<ResourceContractVerifier.Declaration> declarations = new ArrayList<>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for(Path file : files) {
            String name = root.relativize(file).toString();
            name = name.substring(0, name.length() - ".class".length())
                    .replace(File.separatorChar, '.');
            if(name.equals("module-info") || name.equals("package-info"))
                continue;

            Class<?> type;
            try {
                type = Class.forName(name, false, loader);
            } catch(ClassNotFoundException | LinkageError error) {
                throw new IOException("could not inspect compiled class " + name, error);
            }
            FromResource source = type.getAnnotation(FromResource.class);
            if(source != null) {
                declarations.add(new ResourceContractVerifier.Declaration(
                        type.getName(), source.name(), source.version()));
            }
        }
        return declarations;
    }
}
