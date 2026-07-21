package haven;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceContractVerifierTest {
    @Test
    void rejects_empty_discovery() {
        ResourceContractVerifier.Report report = ResourceContractVerifier.verify(
                Collections.emptyList(),
                (name, version) -> {
                    throw new AssertionError("lookup must not run");
                });

        assertFalse(report.passed());
        assertEquals(0, report.declarationCount());
        assertEquals(0, report.resourceCount());
        assertEquals(0, report.attemptedCount());
        assertEquals(0, report.resolvedCount());
        assertTrue(report.describe().contains("no @FromResource declarations found"));
    }

    @Test
    void groups_declarations_and_checks_each_resource_once() {
        List<String> calls = new ArrayList<>();
        List<ResourceContractVerifier.Declaration> declarations = Arrays.asList(
                declaration("Second", "res/z", 2),
                declaration("FirstB", "res/a", 1),
                declaration("FirstA", "res/a", 1));

        ResourceContractVerifier.Report report = ResourceContractVerifier.verify(declarations, (name, version) -> {
            calls.add(name + "@" + version);
            return version;
        });

        assertTrue(report.passed(), report.describe());
        assertEquals(Arrays.asList("res/a@1", "res/z@2"), calls);
        assertEquals(3, report.declarationCount());
        assertEquals(2, report.resourceCount());
        assertEquals(2, report.attemptedCount());
        assertEquals(2, report.resolvedCount());
        assertTrue(report.failures().isEmpty());
        assertEquals("Resource contracts: 3 declarations, 2 resources, 2 attempted, 2 resolved",
                report.describe());
    }

    @Test
    void reports_conflicting_versions_and_continues() {
        List<String> calls = new ArrayList<>();
        List<ResourceContractVerifier.Declaration> declarations = Arrays.asList(
                declaration("Zulu", "res/conflict", 3),
                declaration("Alpha", "res/conflict", 3),
                declaration("New", "res/conflict", 4),
                declaration("Good", "res/good", 7));

        ResourceContractVerifier.Report report = ResourceContractVerifier.verify(declarations, (name, version) -> {
            calls.add(name + "@" + version);
            return version;
        });

        assertFalse(report.passed());
        assertEquals(Collections.singletonList("res/good@7"), calls);
        assertEquals(2, report.resourceCount());
        assertEquals(1, report.attemptedCount());
        assertEquals(1, report.resolvedCount());
        assertTrue(report.describe().contains(
                "res/conflict has conflicting versions 3 [Alpha, Zulu], 4 [New]"));
    }

    @Test
    void accepts_version_zero() {
        ResourceContractVerifier.Report report = ResourceContractVerifier.verify(
                Collections.singletonList(declaration("Zero", "res/zero", 0)),
                (name, version) -> version);

        assertTrue(report.passed(), report.describe());
        assertEquals(1, report.resolvedCount());
    }

    @Test
    void aggregates_version_mismatch_and_lookup_failure() {
        List<ResourceContractVerifier.Declaration> declarations = Arrays.asList(
                declaration("Mismatch", "res/mismatch", 4),
                declaration("Offline", "res/offline", 5),
                declaration("Good", "res/good", 6));

        ResourceContractVerifier.Report report = ResourceContractVerifier.verify(declarations, (name, version) -> {
            if(name.equals("res/mismatch"))
                return version + 1;
            if(name.equals("res/offline"))
                throw new IOException("offline");
            return version;
        });

        assertFalse(report.passed());
        assertEquals(3, report.resourceCount());
        assertEquals(3, report.attemptedCount());
        assertEquals(1, report.resolvedCount());
        assertEquals(2, report.failures().size());
        assertTrue(report.describe().contains("res/mismatch declares version 4 but endpoint returned 5"));
        assertTrue(report.describe().contains("res/offline@5 lookup failed: IOException: offline"));
    }

    @Test
    void reports_invalid_declarations_and_checks_valid_ones() {
        List<ResourceContractVerifier.Declaration> declarations = Arrays.asList(
                null,
                declaration("Blank", "  ", 1),
                declaration("Negative", "res/negative", -1),
                declaration("Good", "res/good", 2));

        ResourceContractVerifier.Report report = ResourceContractVerifier.verify(
                declarations, (name, version) -> version);

        assertFalse(report.passed());
        assertEquals(4, report.declarationCount());
        assertEquals(1, report.resourceCount());
        assertEquals(1, report.attemptedCount());
        assertEquals(1, report.resolvedCount());
        assertTrue(report.describe().contains("null declaration"));
        assertTrue(report.describe().contains("Blank has a blank resource name"));
        assertTrue(report.describe().contains("Negative has invalid version -1"));
        assertThrows(UnsupportedOperationException.class,
                () -> report.failures().add("mutable"));
    }

    @Test
    void rejects_discovery_with_only_invalid_declarations() {
        List<ResourceContractVerifier.Declaration> declarations = Arrays.asList(
                declaration(null, null, 1),
                declaration("", "res/negative", -1));

        ResourceContractVerifier.Report report = ResourceContractVerifier.verify(
                declarations, (name, version) -> version);

        assertFalse(report.passed());
        assertEquals(0, report.resourceCount());
        assertTrue(report.describe().contains("<unknown> has a blank resource name"));
        assertTrue(report.describe().contains("<unknown> has invalid version -1"));
    }

    @Test
    void reports_lookup_failures_without_messages() {
        List<ResourceContractVerifier.Declaration> declarations = Arrays.asList(
                declaration("NullMessage", "res/null-message", 1),
                declaration("EmptyMessage", "res/empty-message", 1));

        ResourceContractVerifier.Report report = ResourceContractVerifier.verify(declarations, (name, version) -> {
            if(name.equals("res/null-message"))
                throw new IOException();
            throw new IOException("");
        });

        assertFalse(report.passed());
        assertTrue(report.describe().contains("res/null-message@1 lookup failed: IOException"));
        assertTrue(report.describe().contains("res/empty-message@1 lookup failed: IOException"));
    }

    @Test
    void stops_after_interrupted_lookup() {
        List<ResourceContractVerifier.Declaration> declarations = Arrays.asList(
                declaration("First", "res/a", 1),
                declaration("Second", "res/b", 2));
        List<String> calls = new ArrayList<>();

        try {
            ResourceContractVerifier.Report report = ResourceContractVerifier.verify(
                    declarations,
                    (name, version) -> {
                        calls.add(name);
                        throw new InterruptedException("stop");
                    });

            assertFalse(report.passed());
            assertEquals(Collections.singletonList("res/a"), calls);
            assertEquals(1, report.attemptedCount());
            assertTrue(report.describe().contains("res/a@1 lookup interrupted"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void requires_declarations_and_lookup() {
        assertThrows(NullPointerException.class,
                () -> ResourceContractVerifier.verify(null, (name, version) -> version));
        assertThrows(NullPointerException.class,
                () -> ResourceContractVerifier.verify(Collections.emptyList(), null));
    }

    private static ResourceContractVerifier.Declaration declaration(String className, String name, int version) {
        return new ResourceContractVerifier.Declaration(className, name, version);
    }
}
