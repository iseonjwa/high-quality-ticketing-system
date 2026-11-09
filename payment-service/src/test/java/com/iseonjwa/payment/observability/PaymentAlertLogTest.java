package com.iseonjwa.payment.observability;

import com.iseonjwa.payment.observability.PaymentAlertLog.PaymentRef;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 건별 알림 로그 줄의 형식을 고정한다. 기대 문자열은 {@code script/check-loki-rules.sh}가 Loki에 넣는 줄과 같다.
 * Loki 규칙의 정규식이 이 형식에 기대므로, 여기를 바꾸면 규칙과 스크립트도 함께 바꿔야 한다.
 */
class PaymentAlertLogTest {
    // 2026-11-09T05:03:12Z = 2026-11-09 14:03:12 KST
    private static final Instant CREATED_AT = Instant.parse("2026-11-09T05:03:12Z");
    private static final PaymentRef PAYMENT = new PaymentRef(1001L, 45L, 7001L, CREATED_AT);

    @Test
    void 결제_운영자_확인_줄은_필드_순서가_고정이다() {
        var line = PaymentAlertLog.line(PaymentAlertLog.PAYMENT_NEEDS_MANUAL_CHECK, PAYMENT, "reason=PG_UNRESOLVED");

        assertEquals("event=PAYMENT_NEEDS_MANUAL_CHECK paymentId=1001 userId=45 reservationId=7001"
                + " paidAt=2026-11-09T14:03:12+09:00 reason=PG_UNRESOLVED", line);
    }

    @Test
    void 환불_운영자_확인_줄에는_환불_번호와_사유가_붙는다() {
        var line = PaymentAlertLog.line(PaymentAlertLog.REFUND_NEEDS_MANUAL_CHECK, PAYMENT,
                "refundId=301 reason=REFUND_REJECTED");

        assertEquals("event=REFUND_NEEDS_MANUAL_CHECK paymentId=1001 userId=45 reservationId=7001"
                + " paidAt=2026-11-09T14:03:12+09:00 refundId=301 reason=REFUND_REJECTED", line);
    }

    @Test
    void 대사_불일치_줄은_결제_키가_없으면_예매_번호만_남는다() {
        var line = PaymentAlertLog.line(PaymentAlertLog.RECONCILIATION_MISMATCH_OPEN, PaymentRef.none(7002L),
                "rule=RC-3");

        assertEquals("event=RECONCILIATION_MISMATCH_OPEN paymentId=- userId=- reservationId=7002 paidAt=- rule=RC-3",
                line);
    }

    @Test
    void DLQ_줄은_결제를_못_읽으면_모두_대시이고_자유_문장_사유는_맨_뒤다() {
        var line = PaymentAlertLog.line(PaymentAlertLog.DEAD_LETTER_OPEN, PaymentRef.none(null),
                "eventKey=payment.command-0-12 reason=역직렬화·봉투 오류: bad json");

        assertEquals("event=DEAD_LETTER_OPEN paymentId=- userId=- reservationId=- paidAt="
                + "- eventKey=payment.command-0-12 reason=역직렬화·봉투 오류: bad json", line);
    }

    @Test
    void 결제_시각은_자정_근처에서도_서울_날짜로_바뀐다() {
        // 2026-11-09T16:30:00Z = 2026-11-10 01:30:00 KST
        assertEquals("2026-11-10T01:30:00+09:00", PaymentAlertLog.paidAt(Instant.parse("2026-11-09T16:30:00Z")));
        assertEquals("-", PaymentAlertLog.paidAt(null));
    }

    @Test
    void 줄에는_개인정보_필드가_없다() {
        var line = PaymentAlertLog.line(PaymentAlertLog.PAYMENT_NEEDS_MANUAL_CHECK, PAYMENT, "reason=PG_UNRESOLVED");

        for (var forbidden : new String[] {"email", "name=", "card", "phone"}) {
            assertFalse(line.toLowerCase().contains(forbidden), forbidden);
        }
    }
}
