package example.orders;

import example.orders.avro.OrderEvent;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.util.ClassSecurityValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class OrderEventTest {
    @Test
    void loadsBothInitializersAndDecodesTheExpectedSpecificRecord() throws Exception {
        var before = ClassSecurityValidator.getGlobal();
        assertFalse(before.isTrusted(OrderEvent.class));
        try {
            var app = new SpringApplication(OrdersService.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            try (var context = app.run()) {
                assertEquals(true, context.getBean("existingInitializerRan"));
                var policy = ClassSecurityValidator.getGlobal();
                assertTrue(policy.isTrusted(OrderEvent.class));
                assertTrue(policy.isTrusted(String.class));
                assertFalse(policy.isTrusted(OrdersService.class));

                var schema = OrderEvent.getClassSchema();
                var event = new GenericData.Record(schema);
                event.put("id", 42L);
                var out = new ByteArrayOutputStream();
                var encoder = EncoderFactory.get().binaryEncoder(out, null);
                new GenericDatumWriter<>(schema).write(event, encoder);
                encoder.flush();
                var decoder = DecoderFactory.get().binaryDecoder(out.toByteArray(), null);
                OrderEvent decoded = new SpecificDatumReader<OrderEvent>(schema).read(null, decoder);
                assertEquals(42L, decoded.getId());
            }
        } finally {
            ClassSecurityValidator.setGlobal(before);
        }
    }
}
