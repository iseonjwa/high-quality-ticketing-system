package com.iseonjwa.reservation.seathold.presentation.messaging;

import com.iseonjwa.common.messaging.consumer.NonRetryableMessageException;
import com.iseonjwa.reservation.seathold.application.SeatHoldRequestedEvent;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code seat-hold.request} 메시지 원문을 봉투(SA 10-2)로 읽고 검사해 {@link SeatHoldRequestedEvent}로 바꾼다.
 * 맞지 않으면 모두 {@link NonRetryableMessageException}이라 재시도 없이 DLQ로 간다.
 *
 * <p>Note: 쓰는 필드만 골라 읽어 모르는 필드는 무시한다(SA 10-4 필드 추가 허용). {@code PaymentResultMessageParser}와 같은 방식이다.
 */
@Component
class SeatHoldRequestedMessageParser {
    static final String PRODUCER = "reservation-service";
    static final String EVENT_TYPE = "SeatHoldRequested";
    static final int VERSION = 1;

    private final ObjectMapper mapper;

    SeatHoldRequestedMessageParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    SeatHoldRequestedEvent parse(String value) {
        var root = read(value);
        if (!integral(root, "version") || root.get("version").asInt() != VERSION) {
            throw invalid("지원하지 않는 version입니다");
        }
        if (!PRODUCER.equals(text(root, "producer"))) {
            throw invalid("producer가 " + PRODUCER + "가 아닙니다");
        }
        if (!EVENT_TYPE.equals(text(root, "eventType"))) {
            throw invalid("eventType이 " + EVENT_TYPE + "가 아닙니다");
        }
        var payload = payload(root);
        return new SeatHoldRequestedEvent(uuid(root, "eventId"), uuid(payload, "requestId"),
                longValue(payload, "userId"), longValue(payload, "scheduleId"), seatIds(payload),
                text(payload, "entryTokenJti"));
    }

    /** DLQ 처리기가 요청 ID만 읽는다. 봉투가 깨졌으면 {@link NonRetryableMessageException}이다. */
    UUID requestId(String value) {
        return uuid(payload(read(value)), "requestId");
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

    private static JsonNode payload(JsonNode root) {
        var payload = root.get("payload");
        if (payload == null || !payload.isObject()) {
            throw invalid("payload가 없습니다");
        }
        return payload;
    }

    private static UUID uuid(JsonNode node, String field) {
        var value = text(node, field);
        if (value == null) {
            throw invalid(field + "가 없습니다");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new NonRetryableMessageException(field + "가 UUID가 아닙니다", e);
        }
    }

    private static List<Long> seatIds(JsonNode payload) {
        var node = payload.get("seatIds");
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw invalid("seatIds가 없습니다");
        }
        var ids = new ArrayList<Long>();
        for (var element : node) {
            if (!element.isIntegralNumber() || !element.canConvertToLong()) {
                throw invalid("seatIds에 정수가 아닌 값이 있습니다");
            }
            ids.add(element.longValue());
        }
        return ids;
    }

    private static long longValue(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(field + "가 정수가 아닙니다");
        }
        return value.longValue();
    }

    private static boolean integral(JsonNode node, String field) {
        var value = node.get(field);
        return value != null && value.isIntegralNumber();
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static NonRetryableMessageException invalid(String reason) {
        return new NonRetryableMessageException("seat-hold.request 봉투가 올바르지 않습니다: " + reason);
    }
}
