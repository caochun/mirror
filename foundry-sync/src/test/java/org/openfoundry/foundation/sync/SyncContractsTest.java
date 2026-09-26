package org.openfoundry.foundation.sync;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.Provenance;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SyncContractsTest {
    @Test
    void mapsSourceRecordAndResolvesSourcePriority() {
        Instant now = Instant.now();
        Provenance provenance = new Provenance("hr", "person-1", "v1", "identity", now, "sync", "hash");
        SourceRecord source = new SourceRecord("hr", "person-1", "UPSERT", now,
                Map.of("person_id", "p-1", "name", "Alice"), provenance);
        MappedRecord mapped = new RecordMapper(new MappingConfig("Person", "person_id", Map.of("name", "name"))).map(source);
        assertEquals("p-1", mapped.key().id());

        ConflictResolver resolver = new ConflictResolver(ConflictResolver.Strategy.SOURCE_PRIORITY,
                Map.of(), Map.of("hr", 1, "legacy", 2));
        var resolution = resolver.resolve(Map.of("name", new ConflictResolver.IncomingValue("Alice", "hr", now, false)),
                Map.of("name", new ConflictResolver.ExistingValue("Alicia", "legacy", now, false)));
        assertEquals("Alice", resolution.accepted().get("name"));
    }
}
