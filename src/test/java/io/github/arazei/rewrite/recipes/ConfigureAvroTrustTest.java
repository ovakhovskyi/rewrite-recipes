package io.github.arazei.rewrite.recipes;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigureAvroTrustTest {
    @Test
    void validatesExplicitPrefixSelection() {
        assertTrue(new ConfigureAvroTrust().validate().isValid());
        assertTrue(new ConfigureAvroTrust(null, List.of("com.example.orders", "com.example.payments")).validate().isValid());
        assertFalse(new ConfigureAvroTrust("com.example.orders.events", List.of("com.example.orders")).validate().isValid());
    }

    @Test
    void rejectsEmptyOrMalformedPrefixes() {
        assertFalse(new ConfigureAvroTrust(null, List.of()).validate().isValid());
        assertFalse(new ConfigureAvroTrust(null, Collections.singletonList(null)).validate().isValid());
        assertFalse(new ConfigureAvroTrust(null, List.of("com.example.orders.")).validate().isValid());
        assertFalse(new ConfigureAvroTrust(null, List.of("com.example.orders.*")).validate().isValid());
    }
}
