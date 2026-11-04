package com.iseonjwa.reservation.reservation.presentation.messaging;

import com.iseonjwa.common.messaging.consumer.NonRetryableMessageException;
import com.iseonjwa.reservation.reservation.application.PaymentResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * {@code payment.result} 메시지 원문을 봉투(SA 10-2)로 읽고 검사해 {@link PaymentResult}로 바꾼다.
 * 맞지 않으면 모두 {@link NonRetryableMessageException}이라 재시도 없이 DLQ로 간다.
 *
 * <p>Note: 쓰는 필드만 골라 읽어 모르는 필드는 무시한다(SA 10-4 필드 추가 허용). 결제 서비스의 RefundRequested 파서와 같은 방식이다.
 */
@Component
@RequiredArgsConstructor
class PaymentResultMessageParser {
    static final String PRODUCER = "payment-service";
    static final int VERSION = 1;

    private final ObjectMapper mapper;

    PaymentResult parse(String value) {
        var root = read(value);
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
        var paymentId = longValue(payload, "paymentId");
        var reservationId = longValue(payload, "reservationId");
        var userId = longValue(payload, "userId");
        var eventType = requiredText(root, "eventType");
        return switch (eventType) {
            case "PaymentApproved" -> PaymentResult.approved(eventId, paymentId, reservationId, userId,
                    amount(payload), requiredText(payload, "currency"), instant(payload, "approvedAt"));
            case "PaymentDeclined" -> PaymentResult.declined(eventId, paymentId, reservationId, userId,
                    requiredText(payload, "reasonCode"));
            case "PaymentRefunded" -> PaymentResult.refunded(eventId, paymentId, reservationId, userId,
                    amount(payload), requiredText(payload, "currency"), instant(payload, "refundedAt"));
            default -> throw invalid("모르는 eventType입니다: " + eventType);
        };
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

    // 금액은 소수 없는 0 이상의 정수(원화)다. 50000.0처럼 소수 표기이거나 문자열이면 거절한다
    private BigDecimal amount(JsonNode payload) {
        if (!integral(payload, "amount") || payload.get("amount").bigIntegerValue().signum() < 0) {
            throw invalid("amount가 0 이상의 정수가 아닙니다");
        }
        return new BigDecimal(payload.get("amount").bigIntegerValue());
    }

    private Instant instant(JsonNode payload, String field) {
        try {
            return Instant.parse(requiredText(payload, field));
        } catch (DateTimeParseException e) {
            throw new NonRetryableMessageException(field + "가 시각 형식이 아닙니다", e);
        }
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
        return new NonRetryableMessageException("payment.result 봉투가 올바르지 않습니다: " + reason);
    }
}
