package iseonjwa.common.event;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EventEnvelopeTest {
    @Test
    void 이벤트_봉투는_SA의_7필드와_UTC시각을_보존한다() {
        var mapper = JsonMapper.builder().build();
        var event = new EventEnvelope<>(UUID.randomUUID(), "PaymentApproved", 1,
                Instant.parse("2026-11-02T11:00:01.123Z"), "payment-service", "trace-1",
                Map.of("paymentId", 9001L));
        var json = mapper.writeValueAsString(event);
        var tree = mapper.readTree(json);
        var restored = mapper.readValue(json, new TypeReference<EventEnvelope<Map<String, Long>>>() {});

        assertEquals(7, tree.size());
        assertEquals("2026-11-02T11:00:01.123Z", tree.get("occurredAt").asString());
        assertEquals(event, restored);
    }
}
