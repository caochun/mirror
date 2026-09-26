package org.openfoundry.foundation.pack;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DomainPackLoaderTest {
    @Test
    void loadsSchemaAndActionManifestFromPack() throws Exception {
        Path directory = Files.createTempDirectory("foundry-pack-");
        Files.writeString(directory.resolve("pack.yaml"), """
                name: people
                version: 0.1.0
                namespace: example.people
                schema:
                  - schema.odl
                actions:
                  - actions.yaml
                """);
        Files.writeString(directory.resolve("schema.odl"), """
                extend schema @namespace(name: "example.people", version: "0.1.0")
                type Person @objectType { id: ID! @primary name: String }
                type RenamePerson @actionType { person: Person! @param name: String! @param }
                """);
        Files.writeString(directory.resolve("actions.yaml"), """
                action: RenamePerson
                version: 1
                effects:
                  - type: updateObject
                    target: person
                    set:
                      name: params.name
                """);

        LoadedDomainPack loaded = new DomainPackLoader().load(directory);

        assertEquals("people", loaded.manifest().name());
        assertEquals(1, loaded.ontology().schema().objectTypes().size());
        assertEquals(1, loaded.actions().size());
    }
}
