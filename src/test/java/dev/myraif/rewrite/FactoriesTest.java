package dev.myraif.rewrite;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FactoriesTest {
    private static final String KEY = Factories.KEY;
    private static final String NAME = "my.app.config.AvroTrustInitializer";

    @Test void mergesMultilineValuesWithoutRewritingOtherEntries() {
        String prefix = "# comment\\\r\nother.foo: a,b\r\n" + KEY + " = first,\\\r\n  second";
        String suffix = "\r\nother.foo2=unchanged\r\n";
        String updated = Factories.register(prefix + suffix, NAME);
        assertEquals(prefix + "," + NAME + suffix, updated);
        assertEquals(updated, Factories.register(updated, NAME));
    }

    @Test void handlesMissingKeyAndUnterminatedFinalLine() {
        assertEquals(KEY + "=" + NAME + "\n", Factories.register("", NAME));
        assertEquals("x=y\n" + KEY + "=" + NAME + "\n", Factories.register("x=y", NAME));
    }

    @Test void handlesEmptyKeyAndTrailingComma() {
        assertEquals(KEY + "=" + NAME, Factories.register(KEY, NAME));
        assertEquals(KEY + "=" + NAME + "\n", Factories.register(KEY + ": \n", NAME));
        assertEquals(KEY + "=a," + NAME, Factories.register(KEY + "=a,", NAME));
    }

    @Test void preservesExistingRegistrationAndEscapedKeys() {
        String existing = KEY + " : a,\\\n  " + NAME + ",b";
        assertEquals(existing, Factories.register(existing, NAME));
        String escaped = KEY.replace(".", "\\.") + ":a";
        assertEquals(escaped + "," + NAME, Factories.register(escaped, NAME));
    }

    @Test void rejectsDuplicateEntriesAndMalformedProperties() {
        assertThrows(IllegalArgumentException.class, () -> Factories.register(KEY + "=a\n" + KEY + "=b", NAME));
        assertThrows(IllegalArgumentException.class, () -> Factories.register("a=b\\", NAME));
        assertThrows(IllegalArgumentException.class, () -> Factories.register("a=\\u12", NAME));
    }
}
