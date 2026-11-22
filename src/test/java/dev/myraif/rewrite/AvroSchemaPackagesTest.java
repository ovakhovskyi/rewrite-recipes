package dev.myraif.rewrite;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AvroSchemaPackagesTest {
    @Test void considersOnlySchemaDefinitionsAndHonorsQualifiedNames() throws Exception {
        String schema = """
            {"type":"record","name":"external.Envelope","namespace":"ignored",
             "fields":[{"name":"data","type":["null",{"type":"map","values":{"type":"array","items":
               {"type":"record","name":"Event","namespace":"orders.avro",
                "fields":[{"name":"status","type":{"type":"enum","name":"Status","symbols":["OK"]}}]}}}],
               "default":{"type":"record","name":"Unrelated","namespace":"never.trust"}}]}
            """;
        assertEquals(Set.of("external", "orders.avro"), AvroSchemaPackages.read(schema));
    }

    @Test void handlesUnionAndRootNamespace() throws Exception {
        assertEquals(Set.of("", "orders.avro"), AvroSchemaPackages.read("""
            ["null",{"type":"fixed","name":"Id","size":2},
            {"type":"record","name":"orders.avro.Event","fields":[]}]
            """));
    }
}
