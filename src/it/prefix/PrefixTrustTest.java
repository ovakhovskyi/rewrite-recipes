package example.orders;

import com.example.orders.RootPackage;
import com.example.orders.events.avro.OrderEvent;
import com.example.ordersarchive.events.SimilarPackage;
import com.example.payments.events.OtherPrefix;
import example.orders.config.AvroTrustInitializer;
import org.apache.avro.util.ClassSecurityValidator;
import org.junit.jupiter.api.Test;

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
