package com.iseonjwa.reservation.reservation.presentation.messaging;

import com.iseonjwa.common.messaging.consumer.NonRetryableMessageException;
import com.iseonjwa.reservation.reservation.application.PaymentResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

// payment.result 봉투 읽기(SA 10-2·10-3). 형식이 틀리면 재시도 없이 DLQ로 가도록 NonRetryableMessageException이어야 한다
class PaymentResultMessageParserTest {
    private static final String EVENT_ID = "7f3c2a9e-1b4d-4c8e-9a51-3e0f6b2d9c11";

    private final PaymentResultMessageParser parser = new PaymentResultMessageParser(JsonMapper.builder().build());

    @Test
    void 승인_이벤트를_읽는다() {
        var result = parser.parse(envelope("PaymentApproved", "payment-service", """
                {"paymentId": 9001, "reservationId": 10, "userId": 42, "amount": 330000, "currency": "KRW",
                 "approvedAt": "2026-11-03T01:00:01.123Z"}"""));

        assertEquals(PaymentResult.Type.APPROVED, result.getType());
        assertEquals(EVENT_ID, result.getEventId().toString());
        assertEquals(9001L, result.getPaymentId());
        assertEquals(10L, result.getReservationId());
        assertEquals(42L, result.getUserId());
        assertEquals(new BigDecimal("330000"), result.getAmount());
        assertEquals("KRW", result.getCurrency());
        assertEquals(Instant.parse("2026-11-03T01:00:01.123Z"), result.getOccurredAt());
    }

    @Test
    void 거절_이벤트를_읽는다() {
        var result = parser.parse(envelope("PaymentDeclined", "payment-service", """
                {"paymentId": 9001, "reservationId": 10, "userId": 42, "reasonCode": "CARD_DECLINED"}"""));

        assertEquals(PaymentResult.Type.DECLINED, result.getType());
        assertEquals("CARD_DECLINED", result.getReasonCode());
        assertNull(result.getAmount());
    }

    @Test
    void 모르는_필드는_무시한다() {
        var result = parser.parse(envelope("PaymentRefunded", "payment-service", """
                {"paymentId": 9001, "reservationId": 10, "userId": 42, "amount": 330000, "currency": "KRW",
                 "refundedAt": "2026-11-03T01:00:01Z", "newField": "x"}"""));

        assertEquals(PaymentResult.Type.REFUNDED, result.getType());
    }

    @Test
    void 형식이_틀리면_재시도하지_않는_오류다() {
        assertThrows(NonRetryableMessageException.class, () -> parser.parse("not json"));
        assertThrows(NonRetryableMessageException.class, () -> parser.parse(envelope("PaymentCanceled",
                "payment-service", "{\"paymentId\": 1, \"reservationId\": 1, \"userId\": 1}")));
        assertThrows(NonRetryableMessageException.class, () -> parser.parse(envelope("PaymentDeclined",
                "reservation-service", "{\"paymentId\": 1, \"reservationId\": 1, \"userId\": 1, \"reasonCode\": \"X\"}")));
        // 금액이 소수 표기면 원화 정수가 아니다
        assertThrows(NonRetryableMessageException.class, () -> parser.parse(envelope("PaymentApproved",
                "payment-service", """
                {"paymentId": 1, "reservationId": 1, "userId": 1, "amount": 1000.5, "currency": "KRW",
                 "approvedAt": "2026-11-03T01:00:01Z"}""")));
    }

    private static String envelope(String eventType, String producer, String payload) {
        return """
                {"eventId": "%s", "eventType": "%s", "version": 1, "occurredAt": "2026-11-03T01:00:02Z",
                 "producer": "%s", "traceId": null, "payload": %s}""".formatted(EVENT_ID, eventType, producer, payload);
    }
}
