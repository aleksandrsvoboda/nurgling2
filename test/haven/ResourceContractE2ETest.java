package haven;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceContractE2ETest {
    private static final String DEFAULT_ENDPOINT = "https://game.havenandhearth.com/res/";

    @Test
    @Tag("resource-e2e")
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void compiled_resource_contracts_resolve_against_live_endpoint() throws IOException {
        Path classes = Paths.get(System.getProperty("resource.contract.classes", "build/classes"));
        URI endpoint = URI.create(System.getProperty("resource.contract.url", DEFAULT_ENDPOINT));
        List<ResourceContractVerifier.Declaration> declarations = ResourceContractDiscovery.discover(
                classes, ResourceContractDiscovery.class.getClassLoader());
        Resource.Pool resources = new Resource.Pool(new Resource.HttpSource(endpoint));

        ResourceContractVerifier.Report report = ResourceContractVerifier.verify(
                declarations,
                (name, version) -> resources.loadwaitint(name, version).ver);

        System.out.println(report.describe());
        assertTrue(report.passed(), report.describe());
    }

}
