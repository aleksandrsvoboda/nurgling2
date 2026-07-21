package haven;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class ResourceContractVerifier {
    interface Lookup {
        int resolve(String name, int version) throws Exception;
    }

    static final class Declaration {
        final String className;
        final String resourceName;
        final int version;

        Declaration(String className, String resourceName, int version) {
            this.className = className;
            this.resourceName = resourceName;
            this.version = version;
        }

        String label() {
            return (className == null || className.isEmpty()) ? "<unknown>" : className;
        }
    }

    static final class Report {
        private final int declarationCount;
        private final int resourceCount;
        private final int attemptedCount;
        private final int resolvedCount;
        private final List<String> failures;

        Report(int declarationCount, int resourceCount, int attemptedCount,
               int resolvedCount, List<String> failures) {
            this.declarationCount = declarationCount;
            this.resourceCount = resourceCount;
            this.attemptedCount = attemptedCount;
            this.resolvedCount = resolvedCount;
            this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
        }

        boolean passed() {
            return attemptedCount == resourceCount && resolvedCount == resourceCount &&
                    failures.isEmpty();
        }

        int declarationCount() {
            return declarationCount;
        }

        int resourceCount() {
            return resourceCount;
        }

        int attemptedCount() {
            return attemptedCount;
        }

        int resolvedCount() {
            return resolvedCount;
        }

        List<String> failures() {
            return failures;
        }

        String describe() {
            StringBuilder message = new StringBuilder(String.format(
                    "Resource contracts: %d declarations, %d resources, %d attempted, %d resolved",
                    declarationCount, resourceCount, attemptedCount, resolvedCount));
            for(String failure : failures)
                message.append(System.lineSeparator()).append(" - ").append(failure);
            return message.toString();
        }
    }

    static Report verify(Collection<Declaration> declarations, Lookup lookup) {
        Objects.requireNonNull(declarations, "declarations");
        Objects.requireNonNull(lookup, "lookup");

        List<String> failures = new ArrayList<>();
        if(declarations.isEmpty())
            failures.add("no @FromResource declarations found");

        Map<String, List<Declaration>> resources = new TreeMap<>();
        for(Declaration declaration : declarations) {
            if(declaration == null) {
                failures.add("null declaration");
            } else if(declaration.resourceName == null || declaration.resourceName.trim().isEmpty()) {
                failures.add(declaration.label() + " has a blank resource name");
            } else if(declaration.version < 0) {
                failures.add(declaration.label() + " has invalid version " + declaration.version);
            } else {
                resources.computeIfAbsent(declaration.resourceName, key -> new ArrayList<>())
                        .add(declaration);
            }
        }

        int attempted = 0;
        int resolved = 0;
        for(Map.Entry<String, List<Declaration>> resource : resources.entrySet()) {
            Map<Integer, List<String>> versions = new TreeMap<>();
            for(Declaration declaration : resource.getValue()) {
                versions.computeIfAbsent(declaration.version, key -> new ArrayList<>())
                        .add(declaration.label());
            }
            if(versions.size() != 1) {
                failures.add(conflict(resource.getKey(), versions));
                continue;
            }

            int declaredVersion = versions.keySet().iterator().next();
            attempted++;
            try {
                int actualVersion = lookup.resolve(resource.getKey(), declaredVersion);
                if(actualVersion == declaredVersion) {
                    resolved++;
                } else {
                    failures.add(resource.getKey() + " declares version " + declaredVersion +
                            " but endpoint returned " + actualVersion);
                }
            } catch(InterruptedException error) {
                Thread.currentThread().interrupt();
                failures.add(resource.getKey() + "@" + declaredVersion + " lookup interrupted");
                break;
            } catch(Exception error) {
                String detail = error.getClass().getSimpleName();
                if(error.getMessage() != null && !error.getMessage().isEmpty())
                    detail += ": " + error.getMessage();
                failures.add(resource.getKey() + "@" + declaredVersion + " lookup failed: " + detail);
            }
        }

        return new Report(declarations.size(), resources.size(), attempted, resolved, failures);
    }

    private static String conflict(String resource, Map<Integer, List<String>> versions) {
        List<String> details = new ArrayList<>();
        for(Map.Entry<Integer, List<String>> version : versions.entrySet()) {
            Collections.sort(version.getValue());
            details.add(version.getKey() + " [" + String.join(", ", version.getValue()) + "]");
        }
        return resource + " has conflicting versions " + String.join(", ", details);
    }
}
