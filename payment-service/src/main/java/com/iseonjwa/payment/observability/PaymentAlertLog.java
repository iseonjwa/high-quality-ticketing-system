package com.iseonjwa.payment.observability;

import com.iseonjwa.payment.payment.domain.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 결제 건 하나에 묶이는 사건을 건별 알림용 로그 한 줄로 남긴다(ADR 0045 결정 4). Loki 규칙(infra/loki/rules)이 이 줄을
 * 읽어 "몇 번 유저가 언제 결제한 건인지"를 슬랙으로 보낸다. 줄은 ERROR 레벨이고 필드 순서가 고정이다.
 *
 * <pre>event=&lt;E&gt; paymentId=&lt;n&gt; userId=&lt;n&gt; reservationId=&lt;n&gt; paidAt=2026-11-09T14:03:12+09:00 &lt;추가 k=v&gt;</pre>
 *
 * <p>값이 없으면 {@code -}다. 내부 ID와 시각만 담고 이메일·이름·카드 정보는 담지 않는다. 채널이 공개이기 때문이다.
 * Loki 규칙의 정규식과 {@code script/check-loki-rules.sh}의 push 줄이 이 형식에 기대므로 바꾸면 함께 고친다.
 *
 * <p>Note: 호출한 트랜잭션 안에서 결제를 읽어 두고 줄은 커밋 뒤({@code afterCommit})에 찍는다. 롤백된 전이가 알림으로
 * 나가면 거짓이고, 같은 사건이 재처리로 두 번 나가기 때문이다. 트랜잭션이 없으면 바로 찍는다.
 * 결제 시각은 {@code payments.created_at}을 Asia/Seoul로 바꾼 값이다. 시스템 시간대에 기대지 않으려고 코드에 고정한다.
 */
@Component
public class PaymentAlertLog {
    public static final String PAYMENT_NEEDS_MANUAL_CHECK = "PAYMENT_NEEDS_MANUAL_CHECK";
    public static final String REFUND_NEEDS_MANUAL_CHECK = "REFUND_NEEDS_MANUAL_CHECK";
    public static final String RECONCILIATION_MISMATCH_OPEN = "RECONCILIATION_MISMATCH_OPEN";
    public static final String DEAD_LETTER_OPEN = "DEAD_LETTER_OPEN";

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter PAID_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");
    private static final String NONE = "-";

    private static final Logger log = LoggerFactory.getLogger(PaymentAlertLog.class);

    private final PaymentRepository payments;

    public PaymentAlertLog(PaymentRepository payments) {
        this.payments = payments;
    }

    /** 결제가 운영자 확인 상태가 됐다. 사유는 {@code PG_AMOUNT_MISMATCH}, {@code PG_UNRESOLVED}다. */
    public void paymentNeedsManualCheck(Long paymentId, String reason) {
        var payment = snapshot(paymentId);
        emit(line(PAYMENT_NEEDS_MANUAL_CHECK, payment, "reason=" + reason));
    }

    /** 환불이 운영자 확인 상태가 됐다. 결제 정보는 환불이 속한 결제에서 읽는다. */
    public void refundNeedsManualCheck(Long paymentId, Long refundId, String reason) {
        var payment = snapshot(paymentId);
        emit(line(REFUND_NEEDS_MANUAL_CHECK, payment, "refundId=" + value(refundId) + " reason=" + reason));
    }

    /** 대사가 새로 찾은 불일치. 결제 키가 없으면 결제 정보는 {@code -}이고 예매 번호만 남는다. */
    public void reconciliationMismatchOpen(String rule, Long paymentId, Long reservationId) {
        var payment = paymentId == null ? PaymentRef.none(reservationId) : snapshot(paymentId, reservationId);
        emit(line(RECONCILIATION_MISMATCH_OPEN, payment, "rule=" + rule));
    }

    /**
     * 처리하지 못한 DLQ 메시지. 결제를 읽지 못하면 {@code -}다. 자유 문장인 사유는 라벨로 뽑지 않도록 맨 뒤에 둔다.
     */
    public void deadLetterOpen(Long paymentId, String eventKey, String reason) {
        var payment = paymentId == null ? PaymentRef.none(null) : snapshot(paymentId);
        emit(line(DEAD_LETTER_OPEN, payment, "eventKey=" + eventKey + " reason=" + reason));
    }

    /** 줄 문자열만 만든다. 단위 테스트가 형식을 고정하는 지점이다. */
    public static String line(String event, PaymentRef payment, String extra) {
        return "event=" + event + " paymentId=" + value(payment.paymentId()) + " userId=" + value(payment.userId())
                + " reservationId=" + value(payment.reservationId()) + " paidAt=" + paidAt(payment.paidAt())
                + " " + extra;
    }

    static String paidAt(Instant createdAt) {
        return createdAt == null ? NONE : PAID_AT.format(createdAt.atZone(SEOUL));
    }

    private static String value(Object value) {
        return value == null ? NONE : value.toString();
    }

    private PaymentRef snapshot(Long paymentId) {
        return snapshot(paymentId, null);
    }

    // 결제를 못 읽어도 알림은 나가야 하므로 예외 없이 빈 값으로 대신한다(예매 번호는 호출한 쪽이 아는 만큼 남긴다)
    private PaymentRef snapshot(Long paymentId, Long fallbackReservationId) {
        if (paymentId == null) return PaymentRef.none(fallbackReservationId);
        try {
            return payments.findById(paymentId)
                    .map(payment -> new PaymentRef(payment.getId(), payment.getUserId(), payment.getReservationId(),
                            payment.getCreatedAt()))
                    .orElseGet(() -> new PaymentRef(paymentId, null, fallbackReservationId, null));
        } catch (RuntimeException exception) {
            log.warn("건별 알림용 결제 조회에 실패했다 paymentId={}", paymentId, exception);
            return new PaymentRef(paymentId, null, fallbackReservationId, null);
        }
    }

    // Note: 트랜잭션 안에서 바로 찍으면 롤백된 전이가 알림으로 나간다. 커밋된 뒤에 찍는다.
    private static void emit(String line) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.error(line);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.error(line);
            }
        });
    }

    /** 줄에 쓰는 결제 정보. {@code paidAt}은 {@code payments.created_at}(UTC)이다. */
    public record PaymentRef(Long paymentId, Long userId, Long reservationId, Instant paidAt) {
        static PaymentRef none(Long reservationId) {
            return new PaymentRef(null, null, reservationId, null);
        }
    }
}
