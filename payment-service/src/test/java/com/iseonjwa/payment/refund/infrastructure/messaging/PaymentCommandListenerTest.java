package com.iseonjwa.payment.refund.infrastructure.messaging;

import com.iseonjwa.common.messaging.consumer.NonRetryableMessageException;
import com.iseonjwa.payment.refund.application.RefundRequestService;
import com.iseonjwa.payment.refund.application.RefundRequestedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 브로커 없이 리스너 메서드를 직접 불러 봉투 검사를 확인한다.
class PaymentCommandListenerTest {
    private static final String EVENT_ID = "7f3c2a9e-1b4d-4c8e-9a51-3e0f6b2d9c11";

    private RefundRequestService service;
    private PaymentCommandListener listener;

    @BeforeEach
    void setUp() {
        service = mock(RefundRequestService.class);
        listener = new PaymentCommandListener(new RefundRequestedMessageParser(JsonMapper.builder().build()), service);
    }

    @Test
    void 올바른_봉투는_eventId와_페이로드를_서비스에_넘긴다() {
        listener.onMessage(message(EVENT_ID, "RefundRequested", "1", "reservation-service", payload("50000", "\"CANCELED\"")));

        var captor = ArgumentCaptor.forClass(RefundRequestedPayload.class);
        verify(service).handle(org.mockito.ArgumentMatchers.eq(UUID.fromString(EVENT_ID)), captor.capture());
        assertEquals(new RefundRequestedPayload(8800001L, 17L, 42L, new BigDecimal("50000"), "KRW", "CANCELED"),
                captor.getValue());
    }

    @Test
    void 모르는_필드는_봉투와_페이로드_모두_무시한다() {
        var body = """
                {"eventId":"%s","eventType":"RefundRequested","version":1,"occurredAt":"2026-11-02T11:00:01.123Z",
                 "producer":"reservation-service","traceId":"abc","futureEnvelopeField":{"a":1},
                 "payload":{"reservationId":8800001,"paymentId":17,"userId":42,"amount":50000,"currency":"KRW",
                            "reason":"CANCELED","futurePayloadField":[1,2,3]}}""".formatted(EVENT_ID);

        listener.onMessage(body);

        verify(service).handle(any(UUID.class), any(RefundRequestedPayload.class));
    }

    @Test
    void eventType이_다르면_영구_오류다() {
        assertRejected(message(EVENT_ID, "BillingPaymentRequested", "1", "reservation-service", payload("50000", "\"CANCELED\"")));
    }

    @Test
    void version이_1이_아니면_영구_오류다() {
        assertRejected(message(EVENT_ID, "RefundRequested", "2", "reservation-service", payload("50000", "\"CANCELED\"")));
    }

    @Test
    void version이_숫자가_아니면_영구_오류다() {
        assertRejected(message(EVENT_ID, "RefundRequested", "\"1\"", "reservation-service", payload("50000", "\"CANCELED\"")));
    }

    @Test
    void producer가_reservation_service가_아니면_영구_오류다() {
        assertRejected(message(EVENT_ID, "RefundRequested", "1", "payment-service", payload("50000", "\"CANCELED\"")));
    }

    @Test
    void eventId가_없거나_UUID가_아니면_영구_오류다() {
        assertRejected("""
                {"eventType":"RefundRequested","version":1,"producer":"reservation-service","payload":%s}"""
                .formatted(payload("50000", "\"CANCELED\"")));
        assertRejected(message("not-a-uuid", "RefundRequested", "1", "reservation-service", payload("50000", "\"CANCELED\"")));
    }

    @Test
    void payload가_없으면_영구_오류다() {
        assertRejected("""
                {"eventId":"%s","eventType":"RefundRequested","version":1,"producer":"reservation-service"}"""
                .formatted(EVENT_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"reservationId", "paymentId", "userId", "amount", "currency", "reason"})
    void 페이로드_필드가_하나라도_없으면_영구_오류다(String missing) {
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("reservationId", "8800001");
        fields.put("paymentId", "17");
        fields.put("userId", "42");
        fields.put("amount", "50000");
        fields.put("currency", "\"KRW\"");
        fields.put("reason", "\"CANCELED\"");
        fields.remove(missing);
        var json = new StringBuilder("{");
        fields.forEach((k, v) -> json.append('"').append(k).append("\":").append(v).append(','));
        json.setLength(json.length() - 1);
        json.append('}');

        assertRejected(message(EVENT_ID, "RefundRequested", "1", "reservation-service", json.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"50000.5", "50000.0", "\"50000\"", "null", "true"})
    void 금액이_정수가_아니면_영구_오류다(String amount) {
        assertRejected(message(EVENT_ID, "RefundRequested", "1", "reservation-service", payload(amount, "\"CANCELED\"")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "{not json", "[]", "\"text\"", "null"})
    void 깨진_JSON이거나_객체가_아니면_영구_오류다(String body) {
        assertRejected(body);
    }

    @Test
    void 값이_null인_메시지는_영구_오류다() {
        assertThrows(NonRetryableMessageException.class, () -> listener.onMessage(null));
        verify(service, never()).handle(any(), any());
    }

    private void assertRejected(String body) {
        assertThrows(NonRetryableMessageException.class, () -> listener.onMessage(body));
        verify(service, never()).handle(any(), any());
    }

    private static String payload(String amount, String reason) {
        return """
                {"reservationId":8800001,"paymentId":17,"userId":42,"amount":%s,"currency":"KRW","reason":%s}"""
                .formatted(amount, reason);
    }

    private static String message(String eventId, String eventType, String version, String producer, String payload) {
        return """
                {"eventId":"%s","eventType":"%s","version":%s,"occurredAt":"2026-11-02T11:00:01.123Z",
                 "producer":"%s","traceId":"4bf92f3577b34da6a3ce929d0e0e4736","payload":%s}"""
                .formatted(eventId, eventType, version, producer, payload);
    }
}
