package haven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

final class ResourceContractDiscovery {
    private static final String CLASS_SUFFIX = ".class";

    static List<ResourceContractVerifier.Declaration> discover(Path root, ClassLoader loader)
            throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(loader, "loader");
        if(!Files.isDirectory(root))
            throw new IOException("compiled classes directory not found: " + root);

        List<Path> files = new ArrayList<>();
        try(Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(CLASS_SUFFIX))
                    .filter(path -> !isMetadata(path))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(files::add);
        }

        List<ResourceContractVerifier.Declaration> declarations = new ArrayList<>();
        for(Path file : files) {
            String name = root.relativize(file).toString();
            name = name.substring(0, name.length() - CLASS_SUFFIX.length())
                    .replace(File.separatorChar, '.');
            try {
                Class<?> type = Class.forName(name, false, loader);
                FromResource source = type.getAnnotation(FromResource.class);
                if(source != null) {
                    declarations.add(new ResourceContractVerifier.Declaration(
                            type.getName(), source.name(), source.version()));
                }
            } catch(ClassNotFoundException | LinkageError error) {
                throw new IOException("could not inspect compiled class " + name, error);
            }
        }
        return declarations;
    }

    private static boolean isMetadata(Path path) {
        String name = path.getFileName().toString();
        return name.equals("module-info.class") || name.equals("package-info.class");
    }
}
