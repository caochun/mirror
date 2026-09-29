package gov.mirror.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.pack.DomainPackLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PackResourcesTest {
    @TempDir Path temporary;

    @Test
    void bundledPackLoadsAndItsTemporaryFilesAreRemovedOnClose() throws Exception {
        Path extracted;
        try (var resources = PackResources.open(null)) {
            extracted = resources.directory();
            var pack = new DomainPackLoader().load(extracted);
            assertEquals("mirror.domain", pack.manifest().namespace());
            assertEquals(37, pack.ontology().schema().objectTypes().size());
            assertEquals(14, pack.actions().size());
            assertTrue(Files.isRegularFile(extracted.resolve("actions/assign-tag.yaml")));
        }
        assertFalse(Files.exists(extracted));
    }

    @Test
    void explicitExternalPackIsUsedAndNeverDeletedOrSilentlyReplaced() throws Exception {
        Path manifest = temporary.resolve("pack.yaml");
        Files.writeString(manifest, "invalid external pack");
        try (var resources = PackResources.open(temporary.toString())) {
            assertEquals(temporary.toRealPath(), resources.directory());
            assertThrows(RuntimeException.class, () -> new DomainPackLoader().load(resources.directory()));
        }
        assertEquals("invalid external pack", Files.readString(manifest));
        assertThrows(IOException.class, () -> PackResources.open(temporary.resolve("missing").toString()));
    }
}
