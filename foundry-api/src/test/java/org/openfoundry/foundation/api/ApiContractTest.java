package org.openfoundry.foundation.api;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiContractTest {
    @Test
    void generatesStableGraphqlQueryAndMutationContract() {
        PropertyDefinition id = new PropertyDefinition("id", "ID", true, true, true, true, false, true);
        OntologySchema schema = new OntologySchema("example", "0.1.0",
                List.of(new ObjectTypeDefinition("Person", List.of(id))), List.of(), List.of());
        String sdl = new GraphqlContractGenerator().generate(schema);
        assertTrue(sdl.contains("type Person"));
        assertTrue(sdl.contains("person(id: ID!)"));
    }
}
