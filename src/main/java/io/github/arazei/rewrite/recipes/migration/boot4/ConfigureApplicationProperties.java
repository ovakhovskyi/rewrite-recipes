package io.github.arazei.rewrite.recipes.migration.boot4;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.marker.Markers;
import org.openrewrite.yaml.YamlIsoVisitor;
import org.openrewrite.yaml.tree.Yaml;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.openrewrite.Tree.randomId;

public class ConfigureApplicationProperties extends Recipe {
    private static final Path APPLICATION = Path.of("src", "main", "resources", "application.yml");
    private static final String TRANSPORT = "management.opentelemetry.tracing.export.otlp.transport";
    private static final String METRICS = "management.otlp.metrics.export.enabled";

    @Override
    public String getDisplayName() {
        return "Configure Spring Boot application telemetry";
    }

    @Override
    public String getDescription() {
        return "Set the OpenTelemetry tracing transport to GRPC and disable OTLP metrics export in the main application YAML document.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new YamlIsoVisitor<ExecutionContext>() {
            @Override
            public Yaml.Documents visitDocuments(Yaml.Documents documents, ExecutionContext ctx) {
                if (!documents.getSourcePath().endsWith(APPLICATION) || documents.getDocuments().isEmpty()) {
                    return documents;
                }
                Yaml.Document main = documents.getDocuments().getFirst();
                Yaml.Block block = main.getBlock();
                Yaml.Mapping mapping;
                if (block instanceof Yaml.Mapping existing) {
                    mapping = existing;
                } else if (block instanceof Yaml.Scalar scalar && scalar.getValue().isEmpty()) {
                    mapping = mapping(false);
                } else {
                    return documents;
                }
                if (hasProperty(mapping, "spring.config.activate.on-profile")
                        || hasProperty(mapping, "spring.config.activate.on-cloud-platform")
                        || hasProperty(mapping, "spring.profiles", value -> !(value instanceof Yaml.Mapping))) {
                    return documents;
                }
                String newline = documents.printAll().contains("\r\n") ? "\r\n" : "\n";
                Yaml.Mapping updated = set(mapping, TRANSPORT, "GRPC", newline);
                updated = set(updated, METRICS, "false", newline);
                if (updated == block) {
                    return documents;
                }
                var entries = new ArrayList<>(documents.getDocuments());
                entries.set(0, main.withBlock(updated));
                return documents.withDocuments(entries);
            }
        };
    }

    private static Yaml.Mapping set(Yaml.Mapping root, String path, String value, String newline) {
        if (!hasProperty(root, path)) {
            return insert(root, path, value, "", newline);
        }
        return visitProperty(root, path, entry -> {
            if (entry.getValue() instanceof Yaml.Scalar old) {
                if (old.getValue().equals(value) && old.getStyle() == Yaml.Scalar.Style.PLAIN && old.getTag() == null) {
                    return entry;
                }
                String prefix = old.getPrefix().isEmpty() ? " " : old.getPrefix();
                return entry.withValue(old.withPrefix(prefix).withStyle(Yaml.Scalar.Style.PLAIN).withTag(null).withValue(value));
            }
            return entry.withValue(scalar(" ", value));
        });
    }

    private static boolean hasProperty(Yaml.Mapping mapping, String path) {
        return hasProperty(mapping, path, value -> true);
    }

    private static boolean hasProperty(Yaml.Mapping mapping, String path, Predicate<Yaml.Block> matchesValue) {
        for (Yaml.Mapping.Entry entry : mapping.getEntries()) {
            String key = entry.getKey().getValue();
            if ((path.equals(key) && matchesValue.test(entry.getValue()))
                    || (path.startsWith(key + ".") && entry.getValue() instanceof Yaml.Mapping child
                    && hasProperty(child, path.substring(key.length() + 1), matchesValue))) {
                return true;
            }
        }
        return false;
    }

    private static Yaml.Mapping visitProperty(Yaml.Mapping mapping, String path, UnaryOperator<Yaml.Mapping.Entry> update) {
        return mapping.withEntries(ListUtils.map(mapping.getEntries(), entry -> {
            String key = entry.getKey().getValue();
            if (path.equals(key)) {
                return update.apply(entry);
            }
            if (path.startsWith(key + ".") && entry.getValue() instanceof Yaml.Mapping child) {
                return entry.withValue(visitProperty(child, path.substring(key.length() + 1), update));
            }
            return entry;
        }));
    }

    private static Yaml.Mapping insert(Yaml.Mapping mapping, String path, String value, String indent, String newline) {
        Yaml.Mapping.Entry parent = null;
        for (Yaml.Mapping.Entry entry : mapping.getEntries()) {
            String key = entry.getKey().getValue();
            if (entry.getValue() instanceof Yaml.Mapping && path.startsWith(key + ".")
                    && (parent == null || key.length() > parent.getKey().getValue().length())) {
                parent = entry;
            }
        }
        var entries = new ArrayList<>(mapping.getEntries());
        if (parent != null) {
            var child = (Yaml.Mapping) parent.getValue();
            String rest = path.substring(parent.getKey().getValue().length() + 1);
            entries.set(entries.indexOf(parent), parent.withValue(insert(child, rest, value, indent + "  ", newline)));
        } else {
            boolean flow = mapping.getOpeningBracePrefix() != null;
            String key = path.split("\\.", 2)[0];
            // A scalar at an intermediate path can coexist with Spring's dotted sub-properties.
            if (entries.stream().anyMatch(entry -> entry.getKey().getValue().equals(key))) {
                entries.add(entry(prefix(mapping, indent, newline), path, scalar(" ", value)));
            } else {
                Yaml.Block child = key.equals(path) ? scalar(" ", value)
                        : insert(mapping(flow), path.substring(key.length() + 1), value,
                                indentation(mapping, indent) + "  ", newline);
                entries.add(entry(prefix(mapping, indent, newline), key, child));
            }
        }
        return mapping.withEntries(entries);
    }

    private static String prefix(Yaml.Mapping mapping, String indent, String newline) {
        if (mapping.getOpeningBracePrefix() != null) {
            return mapping.getEntries().isEmpty() ? "" : ", ";
        }
        return mapping.getEntries().isEmpty() && indent.isEmpty() ? "" : newline + indentation(mapping, indent);
    }

    private static String indentation(Yaml.Mapping mapping, String fallback) {
        for (Yaml.Mapping.Entry entry : mapping.getEntries()) {
            String prefix = entry.getPrefix();
            int line = prefix.lastIndexOf('\n');
            if (line >= 0) {
                return prefix.substring(line + 1);
            }
        }
        return fallback;
    }

    private static Yaml.Mapping mapping(boolean flow) {
        return new Yaml.Mapping(randomId(), Markers.EMPTY, flow ? " " : null, List.of(), flow ? "" : null, null, null);
    }

    private static Yaml.Mapping.Entry entry(String prefix, String key, Yaml.Block value) {
        return new Yaml.Mapping.Entry(randomId(), prefix, Markers.EMPTY, scalar("", key), "", value);
    }

    private static Yaml.Scalar scalar(String prefix, String value) {
        return new Yaml.Scalar(randomId(), prefix, Markers.EMPTY, Yaml.Scalar.Style.PLAIN, null, null, value);
    }
}
