package iseonjwa.payment.infrastructure.messaging;

import iseonjwa.common.response.*;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Profile("local")
@RestController
public class LocalMessagingProbe {
    private final ObjectMapper mapper;
    private final AtomicReference<String> received = new AtomicReference<>();
    public LocalMessagingProbe(ObjectMapper mapper) { this.mapper = mapper; }

    @KafkaListener(topics = "bootstrap.integration", groupId = "payment-service.bootstrap")
    public void receive(String body) {
        received.set(mapper.readTree(body).get("eventId").asString());
    }

    @GetMapping("/api/v1/payments/_system/events/{eventId}")
    public ApiResponse<?> received(@PathVariable String eventId) {
        if (!eventId.equals(received.get())) throw new BusinessException(CommonErrorCode.NOT_FOUND);
        return ApiResponse.success(200, Map.of("eventId", eventId), MDC.get("traceId"));
    }
}
