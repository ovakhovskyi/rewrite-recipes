package example.orders.config;

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
            return "example.orders.avro".equals(type.getPackageName()) || previous.isTrusted(type);
        }
    }
}
