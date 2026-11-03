package com.iseonjwa.payment.refund.infrastructure.messaging;

import com.iseonjwa.common.messaging.consumer.NonRetryableMessageException;
import com.iseonjwa.payment.refund.application.RefundRequestedPayload;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code payment.command} 메시지 원문을 {@code RefundRequested} 봉투로 읽고 검사한다. 맞지 않으면 모두
 * {@link NonRetryableMessageException}이다(events-and-reliability.md 4절 3번).
 *
 * <p>Note: 트리에서 쓰는 필드만 골라 읽으므로 모르는 필드는 무시한다. SA 10-4가 필드 추가를 허용하기 때문이다.
 * 클래스에 바인딩하지 않는 것은 숫자 형식을 직접 검사하려는 이유도 있다. 바인딩하면 {@code 50000.5}나 {@code "50000"}이
 * 설정에 따라 조용히 변환될 수 있다.
 */
@Component
class RefundRequestedMessageParser {
    static final String EVENT_TYPE = "RefundRequested";
    static final int VERSION = 1;
    static final String PRODUCER = "reservation-service";

    private final ObjectMapper mapper;

    RefundRequestedMessageParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    RefundRequestedMessage parse(String value) {
        var root = read(value);
        if (!EVENT_TYPE.equals(text(root, "eventType"))) {
            throw invalid("eventType이 " + EVENT_TYPE + "가 아닙니다");
        }
        if (!integral(root, "version") || root.get("version").asInt() != VERSION) {
            throw invalid("지원하지 않는 version입니다");
        }
        if (!PRODUCER.equals(text(root, "producer"))) {
            throw invalid("producer가 " + PRODUCER + "가 아닙니다");
        }
        var eventId = eventId(root);
        var payload = root.get("payload");
        if (payload == null || !payload.isObject()) {
            throw invalid("payload가 없습니다");
        }
        return new RefundRequestedMessage(eventId, new RefundRequestedPayload(
                longValue(payload, "reservationId"), longValue(payload, "paymentId"), longValue(payload, "userId"),
                amount(payload), requiredText(payload, "currency"), requiredText(payload, "reason")));
    }

    private JsonNode read(String value) {
        if (value == null || value.isBlank()) {
            throw invalid("메시지 본문이 비어 있습니다");
        }
        try {
            var root = mapper.readTree(value);
            if (root == null || !root.isObject()) {
                throw invalid("JSON 객체가 아닙니다");
            }
            return root;
        } catch (JacksonException e) {
            throw new NonRetryableMessageException("JSON을 읽을 수 없습니다", e);
        }
    }

    private UUID eventId(JsonNode root) {
        try {
            return UUID.fromString(requiredText(root, "eventId"));
        } catch (IllegalArgumentException e) {
            throw new NonRetryableMessageException("eventId가 UUID가 아닙니다", e);
        }
    }

    // 금액은 소수 없는 정수 숫자(원화)다. 50000.0처럼 소수 표기이거나 문자열이면 거절한다(contracts.md 2절)
    private BigDecimal amount(JsonNode payload) {
        if (!integral(payload, "amount")) {
            throw invalid("amount가 정수가 아닙니다");
        }
        return new BigDecimal(payload.get("amount").bigIntegerValue());
    }

    private Long longValue(JsonNode payload, String field) {
        if (!integral(payload, field) || !payload.get(field).canConvertToLong()) {
            throw invalid(field + "가 정수가 아닙니다");
        }
        return payload.get(field).longValue();
    }

    private static boolean integral(JsonNode node, String field) {
        var value = node.get(field);
        return value != null && value.isIntegralNumber();
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static String requiredText(JsonNode node, String field) {
        var value = text(node, field);
        if (value == null || value.isBlank()) {
            throw invalid(field + "가 없습니다");
        }
        return value;
    }

    private static NonRetryableMessageException invalid(String reason) {
        return new NonRetryableMessageException("RefundRequested 봉투가 올바르지 않습니다: " + reason);
    }
}
