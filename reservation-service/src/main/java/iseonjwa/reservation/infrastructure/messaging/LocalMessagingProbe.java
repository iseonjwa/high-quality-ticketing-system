package iseonjwa.reservation.infrastructure.messaging;

import iseonjwa.common.event.EventEnvelope;
import iseonjwa.common.response.SuccessResponse;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Profile("local")
@RestController
public class LocalMessagingProbe {
    private static final String TOPIC = "bootstrap.integration";

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public LocalMessagingProbe(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    @Bean
    NewTopic bootstrapTopic() {
        return new NewTopic(TOPIC, 1, (short) 1);
    }

    @PostMapping("/api/v1/reservations/_system/events")
    public ResponseEntity<?> publish(@RequestAttribute Long currentUserId) throws Exception {
        var id = UUID.randomUUID();
        var event = new EventEnvelope<>(id, "BootstrapProbe", 1, Instant.now(), "reservation-service",
                MDC.get("traceId"), Map.of("userId", currentUserId));
        kafka.send(TOPIC, id.toString(), mapper.writeValueAsString(event)).get(5, TimeUnit.SECONDS);
        return ResponseEntity.status(202).body(SuccessResponse.of(202, "접수되었습니다", Map.of("eventId", id), MDC.get("traceId")));
    }
}
