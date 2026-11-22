package io.github.arazei.rewrite.recipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

final class AvroSchemaPackages {
    private static final ObjectMapper JSON = new ObjectMapper();

    private AvroSchemaPackages() {}

    static Set<String> read(String schema) throws IOException {
        Set<String> names = new HashSet<>();
        collect(JSON.readTree(schema), "", names);
        return names;
    }

    private static void collect(JsonNode schema, String inherited, Set<String> names) {
        if (schema == null || schema.isValueNode()) {
            return;
        }
        if (schema.isArray()) {
            schema.forEach(member -> collect(member, inherited, names));
            return;
        }
        JsonNode type = schema.path("type");
        switch (type.asText()) {
            case "record", "error", "enum", "fixed" -> {
                String name = schema.path("name").asText();
                if (name.isEmpty()) {
                    return;
                }
                int dot = name.lastIndexOf('.');
                String namespace = dot >= 0 ? name.substring(0, dot) : schema.path("namespace").asText(inherited);
                names.add(namespace);
                schema.path("fields").forEach(field -> collect(field.get("type"), namespace, names));
            }
            case "array" -> collect(schema.get("items"), inherited, names);
            case "map" -> collect(schema.get("values"), inherited, names);
            default -> {
                if (type.isContainerNode()) {
                    collect(type, inherited, names);
                }
            }
        }
    }
}
