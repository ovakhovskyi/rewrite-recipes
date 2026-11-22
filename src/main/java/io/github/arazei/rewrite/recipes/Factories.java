package io.github.arazei.rewrite.recipes;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Factories {
    static final String KEY = "org.springframework.context.ApplicationContextInitializer";
    private static final Pattern LINE = Pattern.compile("([^\r\n]*)(\r\n|\n|\r|$)");

    private Factories() {}

    // Parse logical entries with the same escaping and separators as java.util.Properties.
    // Edit only the matching value so comments, unrelated keys and line endings survive.
    static String register(String original, String initializer) {
        Matcher lines = LINE.matcher(original);
        int entryStart = 0;
        int insertAt = -1;
        int replaceFrom = -1;
        String suffix = null;
        boolean continued = false;
        while (lines.find() && lines.start() < original.length()) {
            String line = lines.group(1);
            String trimmed = line.stripLeading();
            boolean comment = !continued && (trimmed.startsWith("#") || trimmed.startsWith("!"));
            int end = line.length();
            while (end > 0 && line.charAt(end - 1) == '\\') {
                end--;
            }
            continued = !comment && (line.length() - end) % 2 == 1;
            if (continued) {
                continue;
            }
            Properties entry = new Properties();
            try {
                entry.load(new StringReader(original.substring(entryStart, lines.end())));
            } catch (IOException impossible) {
                throw new AssertionError(impossible);
            }
            int logicalStart = entryStart;
            entryStart = lines.end();
            String value = entry.getProperty(KEY);
            if (value == null) {
                continue;
            }
            if (insertAt >= 0) {
                throw new IllegalArgumentException("Duplicate initializer key in spring.factories");
            }
            insertAt = lines.end(1);
            boolean registered = Arrays.stream(value.split(",")).map(String::trim).anyMatch(initializer::equals);
            if (!registered) {
                replaceFrom = insertAt;
                if (value.isBlank()) {
                    replaceFrom = logicalStart;
                    suffix = KEY + "=" + initializer;
                } else {
                    suffix = (value.stripTrailing().endsWith(",") ? "" : ",") + initializer;
                }
            }
        }
        if (continued) {
            throw new IllegalArgumentException("Unterminated continuation in spring.factories");
        }
        if (insertAt >= 0) {
            return suffix == null ? original : original.substring(0, replaceFrom) + suffix + original.substring(insertAt);
        }
        String newline = original.contains("\r\n") ? "\r\n" : original.contains("\r") ? "\r" : "\n";
        boolean atLineStart = original.isEmpty() || original.endsWith("\n") || original.endsWith("\r");
        return original + (atLineStart ? "" : newline) + KEY + "=" + initializer + newline;
    }
}
