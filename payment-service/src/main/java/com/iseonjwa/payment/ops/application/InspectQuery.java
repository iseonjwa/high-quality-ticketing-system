package com.iseonjwa.payment.ops.application;

import com.iseonjwa.payment.ops.domain.OpsInspectionRepository;
import com.iseonjwa.payment.ops.domain.OpsInspectionRepository.OutboxView;
import com.iseonjwa.payment.ops.domain.OpsInspectionRepository.PaymentView;
import com.iseonjwa.payment.ops.domain.OpsInspectionRepository.RefundView;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgClient.Approved;
import com.iseonjwa.payment.pg.application.PgClient.Declined;
import com.iseonjwa.payment.pg.application.PgClient.NotFound;
import com.iseonjwa.payment.pg.application.PgClient.PaymentLookupResult;
import com.iseonjwa.payment.pg.application.PgClient.RefundLookupResult;
import com.iseonjwa.payment.pg.application.PgClient.RefundRejected;
import com.iseonjwa.payment.pg.application.PgClient.Refunded;
import com.iseonjwa.payment.pg.application.PgClient.Unknown;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.PgRefundId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code inspect}: 결제·환불·예매 하나의 상태, 이력, 환불, 같은 예매 키의 Outbox와 {@code dead_letters}, 감사 기록을 묶어
 * 출력한다. 읽기 전용이고 1인 명령이다. {@code --with-pg}면 PG를 조회해 DB와 나란히 보이고 상태·금액이 맞는지 표시한다.
 *
 * <p>Note: PG를 조회하지 못해도 거부가 아니다. 읽기 전용이라 바꾸는 것이 없고, 조회하지 못했다는 사실 자체가 조사
 * 결과이기 때문에 그 사실만 보여 주고 종료 코드는 0이다. PG 호출은 복구 게이트(Circuit Breaker·Bulkhead)를 거치지 않는다.
 * 게이트는 떠 있는 서비스의 호출량을 지키는 장치이고, 이 JVM은 사람이 한 건씩 부르는 별도 프로세스다.
 * 출력에는 카드 정보·비밀값을 넣지 않는다(PG 결과 타입에도 없다).
 */
@Component
public class InspectQuery implements OpsQuery {
    public static final String NAME = "inspect";
    private static final String NONE = "-";
    private static final int RECENT_RESOLVED_DAYS = 7;

    private final OpsInspectionRepository inspection;
    private final ObjectProvider<PgClient> pgClient;

    public InspectQuery(OpsInspectionRepository inspection, ObjectProvider<PgClient> pgClient) {
        this.inspection = inspection;
        this.pgClient = pgClient;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> options() {
        return Set.of("payment-id", "refund-id", "reservation-id", "with-pg");
    }

    @Override
    public List<String> report(OpsArguments arguments) {
        var paymentId = arguments.longValue("payment-id");
        var refundId = arguments.longValue("refund-id");
        var reservationId = arguments.longValue("reservation-id");
        var given = (paymentId != null ? 1 : 0) + (refundId != null ? 1 : 0) + (reservationId != null ? 1 : 0);
        if (given != 1) {
            throw new OpsUsageException("--payment-id, --refund-id, --reservation-id 중 하나만 지정해야 합니다");
        }

        PaymentView payment = null;
        if (paymentId != null) {
            payment = inspection.findPayment(paymentId)
                    .orElseThrow(() -> new OpsRejection("결제가 없습니다: payment-id=" + paymentId));
        } else if (refundId != null) {
            var owner = inspection.findPaymentIdByRefundId(refundId)
                    .orElseThrow(() -> new OpsRejection("환불이 없습니다: refund-id=" + refundId));
            payment = inspection.findPayment(owner).orElseThrow();
        } else {
            payment = inspection.findPaymentIdByReservationId(reservationId).flatMap(inspection::findPayment)
                    .orElse(null);
        }
        var key = String.valueOf(payment != null ? payment.reservationId() : reservationId);
        var outbox = inspection.findOutboxByKey(key);
        var deadLetters = inspection.findDeadLettersByKey(key);
        if (payment == null && outbox.isEmpty() && deadLetters.isEmpty()) {
            throw new OpsRejection("예매에 해당하는 결제, Outbox, dead_letters가 모두 없습니다: reservation-id=" + reservationId);
        }

        var lines = new ArrayList<String>();
        RefundView refund = null;
        lines.add("예매 키: " + key);
        if (payment == null) {
            lines.add("[결제] 없음");
        } else {
            refund = inspection.findRefundByPaymentId(payment.id()).orElse(null);
            addPayment(lines, payment);
            addHistories(lines, payment.id());
            addRefund(lines, refund);
        }
        addOutbox(lines, outbox);
        addDeadLetters(lines, deadLetters);
        addManualActionLogs(lines, payment, refund, outbox, deadLetters);
        addMismatches(lines, payment, reservationId != null ? reservationId : payment != null ? payment.reservationId() : null);
        if (arguments.flag("with-pg")) {
            addPgComparison(lines, payment, refund);
        }
        return lines;
    }

    private void addPayment(List<String> lines, PaymentView payment) {
        lines.add("[결제] id=" + payment.id() + " 번호=" + payment.paymentNo() + " 예매=" + payment.reservationId()
                + " 사용자=" + payment.userId());
        lines.add("  상태=" + payment.status() + " 금액=" + payment.amount().toPlainString() + " " + payment.currency()
                + " 방식=" + payment.method());
        lines.add("  PG 주문=" + payment.pgOrderId() + " PG 거래=" + or(payment.pgTxId()) + " PG 승인 금액="
                + (payment.pgApprovedAmount() == null ? NONE : payment.pgApprovedAmount().toPlainString()));
        lines.add("  사유(decline_code)=" + or(payment.declineCode()) + " 재확인 횟수=" + payment.unknownRetryCount()
                + " 다음 확인=" + or(payment.nextCheckAt()));
        lines.add("  승인=" + or(payment.approvedAt()) + " 환불=" + or(payment.refundedAt()) + " 생성="
                + payment.createdAt() + " 수정=" + payment.updatedAt());
    }

    private void addHistories(List<String> lines, long paymentId) {
        var histories = inspection.findHistories(paymentId);
        lines.add("[상태 이력] " + histories.size() + "건");
        for (var history : histories) {
            lines.add("  #" + history.id() + " " + history.createdAt() + " " + history.targetType() + " "
                    + or(history.fromStatus()) + " -> " + history.toStatus() + " 사유=" + or(history.reason())
                    + " actor=" + history.actor());
        }
    }

    private void addRefund(List<String> lines, RefundView refund) {
        if (refund == null) {
            lines.add("[환불] 없음");
            return;
        }
        lines.add("[환불] id=" + refund.id() + " 상태=" + refund.status() + " 금액=" + refund.amount().toPlainString()
                + " 사유=" + refund.reason() + " 경로=" + refund.source());
        lines.add("  PG 환불 ID=" + refund.pgRefundId() + " PG 환불 참조=" + or(refund.pgRefundRef()) + " 시도="
                + refund.attempt() + " 다음 확인=" + or(refund.nextCheckAt()));
        lines.add("  PG 요청=" + or(refund.pgRequestedAt()) + " 환불=" + or(refund.refundedAt()) + " 생성="
                + refund.createdAt() + " 수정=" + refund.updatedAt());
    }

    private void addOutbox(List<String> lines, List<OutboxView> outbox) {
        lines.add("[Outbox] " + outbox.size() + "건");
        for (var row : outbox) {
            lines.add("  id=" + row.id() + " 상태=" + row.status() + " 시도=" + row.attempts() + " 이벤트="
                    + row.eventType() + " 토픽=" + row.topic() + " 다음 시도=" + row.nextAttemptAt() + " 오류="
                    + or(row.lastError()));
        }
    }

    private void addDeadLetters(List<String> lines, List<OpsInspectionRepository.DeadLetterView> deadLetters) {
        lines.add("[dead_letters] " + deadLetters.size() + "건");
        for (var letter : deadLetters) {
            lines.add("  id=" + letter.id() + " 상태=" + letter.status() + " 처리=" + or(letter.resolution()) + " 토픽="
                    + or(letter.sourceTopic()) + " 이벤트=" + or(letter.eventType()) + " 사유=" + letter.reason());
            lines.add("    원문 앞부분: " + singleLine(letter.payloadHead()));
        }
    }

    private void addManualActionLogs(List<String> lines, PaymentView payment, RefundView refund, List<OutboxView> outbox,
            List<OpsInspectionRepository.DeadLetterView> deadLetters) {
        var logs = inspection.findManualActionLogs(payment == null ? List.of() : List.of(payment.id()),
                refund == null ? List.of() : List.of(refund.id()), outbox.stream().map(OutboxView::id).toList(),
                deadLetters.stream().map(OpsInspectionRepository.DeadLetterView::id).toList());
        lines.add("[Ops 기록] " + logs.size() + "건");
        for (var log : logs) {
            lines.add("  #" + log.id() + " " + log.performedAt() + " " + log.command() + " " + log.targetType() + " "
                    + log.targetId() + " " + log.result() + " " + or(log.fromStatus()) + " -> " + or(log.toStatus())
                    + " 실행자=" + log.operator() + " 승인자=" + or(log.approver()) + " 메모=" + log.reason());
        }
    }

    private void addMismatches(List<String> lines, PaymentView payment, Long reservationId) {
        var mismatches = inspection.findMismatches(payment == null ? null : payment.id(), reservationId,
                RECENT_RESOLVED_DAYS);
        lines.add("[대사 불일치] " + mismatches.size() + "건(OPEN과 최근 " + RECENT_RESOLVED_DAYS + "일 안에 닫힌 RESOLVED)");
        for (var mismatch : mismatches) {
            lines.add("  #" + mismatch.id() + " " + mismatch.rule() + " " + mismatch.status() + " 처음 발견="
                    + mismatch.firstDetectedAt() + " 마지막 확인=" + mismatch.lastSeenAt() + " 닫힘="
                    + or(mismatch.resolvedAt()));
            lines.add("    상세: " + singleLine(mismatch.detail()));
        }
    }

    private void addPgComparison(List<String> lines, PaymentView payment, RefundView refund) {
        lines.add("[PG 대조]");
        var client = pgClient.getIfAvailable();
        if (client == null) {
            lines.add("  PG 클라이언트가 설정되어 있지 않아 조회하지 못했다");
            return;
        }
        if (payment == null) {
            lines.add("  결제가 없어 PG를 조회하지 않는다");
            return;
        }
        lines.add("  결제: " + comparePayment(client, payment));
        if (refund != null) {
            lines.add("  환불: " + compareRefund(client, payment, refund));
        }
    }

    private String comparePayment(PgClient client, PaymentView payment) {
        PaymentLookupResult result;
        try {
            result = client.findPayment(new PgOrderId(payment.pgOrderId()));
        } catch (RuntimeException exception) {
            return "PG를 조회하지 못했다(" + exception.getClass().getSimpleName() + ")";
        }
        return switch (result) {
            case Approved approved -> {
                var amountMatches = approved.matches(payment.amount(), payment.currency());
                var statusMatches = payment.status().equals("APPROVED") || payment.status().equals("REFUNDED");
                var txMatches = payment.pgTxId() == null || payment.pgTxId().equals(approved.paymentRef().value());
                yield "PG=승인 금액=" + approved.amount().toPlainString() + " " + approved.currency() + " 거래="
                        + approved.paymentRef().value() + " / DB=" + payment.status() + " 금액="
                        + payment.amount().toPlainString() + " " + payment.currency() + " -> "
                        + verdict(statusMatches && amountMatches && txMatches,
                                (statusMatches ? "" : "상태가 PG 승인과 다르다. ")
                                        + (amountMatches ? "" : "금액·통화가 다르다. ")
                                        + (txMatches ? "" : "PG 거래 식별자가 다르다."));
            }
            case Declined declined -> "PG=거절(" + declined.reason() + ") / DB=" + payment.status() + " -> "
                    + verdict(payment.status().equals("DECLINED"), "DB가 거절이 아니다");
            case NotFound ignored -> "PG=해당 주문 없음 / DB=" + payment.status() + " -> "
                    + (payment.status().equals("APPROVED") || payment.status().equals("REFUNDED")
                    ? "불일치: DB는 승인인데 PG에 주문이 없다" : "판단 불가: PG에 승인 기록이 없다");
            case Unknown unknown -> "PG를 조회하지 못했다(결과 불확실: " + unknown.cause() + ")";
        };
    }

    private String compareRefund(PgClient client, PaymentView payment, RefundView refund) {
        if (payment.pgTxId() == null) {
            return "PG 거래 식별자가 없어 환불을 조회하지 못했다";
        }
        RefundLookupResult result;
        try {
            result = client.findRefund(new PgRefundId(refund.pgRefundId()), new PgPaymentRef(payment.pgTxId()));
        } catch (RuntimeException exception) {
            return "PG를 조회하지 못했다(" + exception.getClass().getSimpleName() + ")";
        }
        return switch (result) {
            case Refunded refunded -> {
                var amountMatches = refund.amount().compareTo(refunded.amount()) == 0
                        && payment.currency().equals(refunded.currency());
                var statusMatches = refund.status().equals("REFUNDED");
                yield "PG=환불 완료 금액=" + refunded.amount().toPlainString() + " " + refunded.currency() + " / DB="
                        + refund.status() + " 금액=" + refund.amount().toPlainString() + " -> "
                        + verdict(statusMatches && amountMatches, (statusMatches ? "" : "DB가 환불 완료가 아니다. ")
                                + (amountMatches ? "" : "금액·통화가 다르다."));
            }
            case RefundRejected rejected -> "PG=환불 거절(" + rejected.reason() + ") / DB=" + refund.status() + " -> "
                    + verdict(!refund.status().equals("REFUNDED"), "DB는 환불 완료인데 PG는 거절이다");
            case NotFound ignored -> "PG=해당 환불 없음 / DB=" + refund.status() + " -> "
                    + (refund.status().equals("REFUNDED") ? "불일치: DB는 환불 완료인데 PG에 환불이 없다"
                    : "판단 불가: PG에 환불 기록이 없다");
            case Unknown unknown -> "PG를 조회하지 못했다(결과 불확실: " + unknown.cause() + ")";
        };
    }

    private static String verdict(boolean matches, String mismatch) {
        return matches ? "일치" : "불일치: " + mismatch.strip();
    }

    private static String or(Object value) {
        return value == null ? NONE : value.toString();
    }

    private static String singleLine(String value) {
        return value == null ? NONE : value.replaceAll("\\s+", " ");
    }
}
