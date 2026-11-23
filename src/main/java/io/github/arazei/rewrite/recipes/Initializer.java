package io.github.arazei.rewrite.recipes;

import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class Initializer {
    private static final Pattern PACKAGE_CONSTANT = Pattern.compile(
            "private static final String TRUSTED_PACKAGE_\\d+ = \"([\\p{javaJavaIdentifierPart}.]+)\";");
    private static final Pattern PREFIX_CONSTANT = Pattern.compile(
            "private static final String TRUSTED_PACKAGE_PREFIX_\\d+ = \"([\\p{javaJavaIdentifierPart}.]+)\";");
    private static final Pattern LEGACY_PACKAGE_CHECK = Pattern.compile(
            "\"([\\p{javaJavaIdentifierPart}.]+)\"\\.equals\\(type\\.getPackageName\\(\\)\\)");
    private static final String OR = "\n                    || ";

    private Initializer() {}

    static String source(String base, List<String> modelPackages) {
        return source(base, modelPackages, false);
    }

    static String prefixSource(String base, List<String> prefixes) {
        return source(base, prefixes, true);
    }

    private static String source(String base, List<String> packages, boolean prefixes) {
        StringBuilder constants = new StringBuilder();
        StringJoiner checks = new StringJoiner(OR);
        int index = 1;
        for (String name : packages) {
            String constant = (prefixes ? "TRUSTED_PACKAGE_PREFIX_" : "TRUSTED_PACKAGE_") + index++;
            constants.append("    private static final String ").append(constant)
                    .append(" = \"").append(name).append("\";\n");
            checks.add(prefixes ? "matchesPackage(packageName, " + constant + ")" : constant + ".equals(packageName)");
        }
        checks.add("previous.isTrusted(type)");
        String prefixMatcher = prefixes ? """

                        private static boolean matchesPackage(String packageName, String prefix) {
                            int length = prefix.length();
                            return packageName.startsWith(prefix)
                                    && (packageName.length() == length || packageName.charAt(length) == '.');
                        }
                """ : "";
        return render(base, constants.append('\n').toString(),
                "            String packageName = type.getPackageName();\n", checks.toString(), prefixMatcher);
    }

    private static String legacySource(String base, List<String> modelPackages) {
        String packageChecks = modelPackages.stream()
                .map(name -> "\"" + name + "\".equals(type.getPackageName())")
                .collect(Collectors.joining(OR));
        return render(base, "", "", packageChecks + " || previous.isTrusted(type)", "");
    }

    private static String render(String base, String constants, String packageNameDeclaration, String checks,
                                 String prefixMatcher) {
        return """
                package %s.config;

                import org.apache.avro.util.ClassSecurityValidator;
                import org.apache.avro.util.ClassSecurityValidator.ClassSecurityPredicate;
                import org.springframework.context.ApplicationContextInitializer;
                import org.springframework.context.ConfigurableApplicationContext;

                public final class AvroTrustInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
                %s    @Override
                    public void initialize(ConfigurableApplicationContext context) {
                        synchronized (ClassSecurityValidator.class) {
                            ClassSecurityPredicate current = ClassSecurityValidator.getGlobal();
                            if (!(current instanceof PackageTrust)) {
                                ClassSecurityValidator.setGlobal(new PackageTrust(current));
                            }
                        }
                    }

                    private record PackageTrust(ClassSecurityPredicate previous) implements ClassSecurityPredicate {
                        @Override
                        public boolean isTrusted(Class<?> type) {
                %s            return %s;
                        }
                %s    }
                }
                """.formatted(base, constants, packageNameDeclaration, checks, prefixMatcher);
    }

    static boolean isGeneratedSource(String base, String candidate) {
        List<String> prefixes = packagesIn(candidate, PREFIX_CONSTANT);
        if (!prefixes.isEmpty()) {
            return candidate.equals(prefixSource(base, prefixes).strip());
        }
        List<String> packages = packagesIn(candidate, PACKAGE_CONSTANT);
        if (!packages.isEmpty()) {
            return candidate.equals(source(base, packages).strip());
        }
        packages = packagesIn(candidate, LEGACY_PACKAGE_CHECK);
        return !packages.isEmpty() && candidate.equals(legacySource(base, packages).strip());
    }

    private static List<String> packagesIn(String source, Pattern pattern) {
        return pattern.matcher(source).results()
                .map(match -> match.group(1))
                .toList();
    }
}
