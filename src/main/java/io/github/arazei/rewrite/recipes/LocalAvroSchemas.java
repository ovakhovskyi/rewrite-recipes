package io.github.arazei.rewrite.recipes;

import org.openrewrite.FileAttributes;
import org.openrewrite.SourceFile;
import org.openrewrite.quark.Quark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class LocalAvroSchemas {
    private LocalAvroSchemas() {}

    static Optional<Path> moduleDirectory(SourceFile pom) {
        Path path = pom.getSourcePath().normalize();
        FileAttributes attributes = pom.getFileAttributes();
        if (attributes == null || path.startsWith("..")) {
            return Optional.empty();
        }
        if (path.isAbsolute()) {
            return matches(path, attributes) ? Optional.of(path.getParent()) : Optional.empty();
        }
        // Maven's launch and Rewrite's source roots can differ (e.g. a POM below the Git root).
        // Match the scanned POM instead of accepting the first directory containing a pom.xml.
        String mavenRoot = System.getProperty("maven.multiModuleProjectDirectory");
        if (mavenRoot != null && !mavenRoot.isBlank()) {
            Optional<Path> module = findModule(Path.of(mavenRoot), path, attributes);
            if (module.isPresent()) {
                return module;
            }
        }
        return findModule(Path.of(""), path, attributes);
    }

    private static Optional<Path> findModule(Path start, Path pom, FileAttributes attributes) {
        for (Path root = start.toAbsolutePath().normalize(); root != null; root = root.getParent()) {
            Path candidate = root.resolve(pom);
            if (matches(candidate, attributes)) {
                return Optional.of(candidate.getParent());
            }
        }
        return Optional.empty();
    }

    static Set<String> read(Path module, Path relativeSchema, Quark schema) throws IOException {
        Path directory = module.toRealPath();
        Path candidate = directory.resolve(relativeSchema).normalize();
        if (!candidate.startsWith(directory) || !candidate.equals(candidate.toRealPath()) ||
                !matches(candidate, schema.getFileAttributes())) {
            throw new IOException("Cannot verify local Avro schema: " + schema.getSourcePath());
        }
        String text = Files.readString(candidate);
        return AvroSchemaPackages.read(text.startsWith("\uFEFF") ? text.substring(1) : text);
    }

    private static boolean matches(Path path, FileAttributes scanned) {
        if (scanned == null || !Files.isRegularFile(path)) {
            return false;
        }
        FileAttributes local = FileAttributes.fromPath(path);
        return local != null && local.getSize() == scanned.getSize() &&
                Objects.equals(local.getCreationTime(), scanned.getCreationTime()) &&
                Objects.equals(local.getLastModifiedTime(), scanned.getLastModifiedTime());
    }
}
