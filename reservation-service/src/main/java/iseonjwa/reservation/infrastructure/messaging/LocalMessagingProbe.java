package iseonjwa.reservation.infrastructure.messaging;

import iseonjwa.common.event.EventEnvelope;
import iseonjwa.common.response.ApiResponse;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.MDC;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Profile("local")
@RestController
public class LocalMessagingProbe {
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    public LocalMessagingProbe(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka; this.mapper = mapper;
    }

    @Bean NewTopic bootstrapTopic() { return new NewTopic("bootstrap.integration", 1, (short) 1); }

    @PostMapping("/api/v1/reservations/_system/events")
    public ResponseEntity<?> publish(@RequestAttribute Long currentUserId) throws Exception {
        var id = UUID.randomUUID();
        var event = new EventEnvelope<>(id, "BootstrapProbe", 1, Instant.now(), "reservation-service",
                MDC.get("traceId"), Map.of("userId", currentUserId));
        kafka.send("bootstrap.integration", id.toString(), mapper.writeValueAsString(event)).get(5, TimeUnit.SECONDS);
        return ResponseEntity.status(202).body(ApiResponse.success(202, Map.of("eventId", id), MDC.get("traceId")));
    }
}
