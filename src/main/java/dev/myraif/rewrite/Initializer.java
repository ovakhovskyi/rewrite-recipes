package dev.myraif.rewrite;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class Initializer {
    private static final Pattern PACKAGE_CHECK = Pattern.compile(
            "\"([\\p{javaJavaIdentifierPart}.]+)\"\\.equals\\(type\\.getPackageName\\(\\)\\)");

    private Initializer() {}

    static String source(String base, List<String> modelPackages) {
        String packageChecks = modelPackages.stream()
                .map(name -> "\"" + name + "\".equals(type.getPackageName())")
                .collect(Collectors.joining("\n                    || "));
        return """
                package %s.config;

                import org.apache.avro.util.ClassSecurityValidator;
                import org.apache.avro.util.ClassSecurityValidator.ClassSecurityPredicate;
                import org.springframework.context.ApplicationContextInitializer;
                import org.springframework.context.ConfigurableApplicationContext;

                public final class AvroTrustInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
                    @Override
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
                            return %s || previous.isTrusted(type);
                        }
                    }
                }
                """.formatted(base, packageChecks);
    }

    static boolean isGeneratedSource(String base, String source) {
        List<String> packages = PACKAGE_CHECK.matcher(source).results()
                .map(match -> match.group(1))
                .toList();
        return !packages.isEmpty() && source.equals(source(base, packages).strip());
    }
}
