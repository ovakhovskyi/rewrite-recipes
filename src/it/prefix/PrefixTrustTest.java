package example.orders;

import example.orders.config.AvroTrustInitializer;
import org.apache.avro.util.ClassSecurityValidator;
import org.junit.jupiter.api.Test;
import ua.raif.RootPackage;
import ua.raif.events.avro.OrderEvent;
import ua.raiffeisen.events.OtherPrefix;
import ua.raifextra.events.SimilarPackage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrefixTrustTest {
    @Test
    void trustsConfiguredPackageBoundariesAndDelegatesOtherClasses() {
        var original = ClassSecurityValidator.getGlobal();
        try {
            ClassSecurityValidator.setGlobal(type -> type == String.class);
            new AvroTrustInitializer().initialize(null);
            var trust = ClassSecurityValidator.getGlobal();
            assertTrue(trust.isTrusted(RootPackage.class));
            assertTrue(trust.isTrusted(OrderEvent.class));
            assertTrue(trust.isTrusted(OtherPrefix.class));
            assertFalse(trust.isTrusted(SimilarPackage.class));
            assertTrue(trust.isTrusted(String.class));
        } finally {
            ClassSecurityValidator.setGlobal(original);
        }
    }
}
