package com.iseonjwa.payment.ops.application;

import com.iseonjwa.payment.pg.application.PgClient;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 계획 해시와 {@code manual_action_logs.pg_evidence}에 들어가는 PG 근거 요약(JSON). 결과 종류, 금액, 통화, PG 식별자만
 * 담는다. 카드 정보와 비밀값은 PG 결과 타입에도 없고, 조회마다 달라지는 시각도 넣지 않는다.
 *
 * <p>Note: 같은 PG 결과는 항상 같은 문자열이어야 해시가 안정적이다. 키 순서는 넣은 순서로 고정하고 금액은 소수 자리 표기
 * (50000과 50000.0)를 하나로 맞춘다.
 */
final class PgEvidence {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PgEvidence() {}

    static String of(PgClient.PaymentLookupResult result) {
        var evidence = new LinkedHashMap<String, Object>();
        switch (result) {
            case PgClient.Approved approved -> approval(evidence, "lookup", "Approved", approved.paymentRef().value(),
                    approved.amount(), approved.currency());
            case PgClient.Declined declined -> {
                evidence.put("lookup", "Declined");
                evidence.put("reason", declined.reason().name());
            }
            case PgClient.NotFound notFound -> evidence.put("lookup", "NotFound");
            case PgClient.Unknown unknown -> {
                evidence.put("lookup", "Unknown");
                evidence.put("cause", unknown.cause().name());
            }
        }
        return write(evidence);
    }

    static String of(PgClient.RefundLookupResult result) {
        var evidence = new LinkedHashMap<String, Object>();
        switch (result) {
            case PgClient.Refunded refunded -> {
                evidence.put("lookup", "Refunded");
                evidence.put("refundRef", refunded.refundRef());
                evidence.put("amount", plain(refunded.amount()));
                evidence.put("currency", refunded.currency());
            }
            case PgClient.RefundRejected rejected -> {
                evidence.put("lookup", "RefundRejected");
                evidence.put("reason", rejected.reason().name());
            }
            case PgClient.NotFound notFound -> evidence.put("lookup", "NotFound");
            case PgClient.Unknown unknown -> {
                evidence.put("lookup", "Unknown");
                evidence.put("cause", unknown.cause().name());
            }
        }
        return write(evidence);
    }

    /** 조회가 {@code NotFound}였던 결제에 늦은 승인 방지를 적용한 결과까지 담은 근거. */
    static String ofLateApproval(PgClient.LateApprovalResult result) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("lookup", "NotFound");
        switch (result) {
            case PgClient.Prevented prevented -> evidence.put("lateApproval", "Prevented");
            case PgClient.AlreadyApproved approved -> approval(evidence, "lateApproval", "AlreadyApproved",
                    approved.paymentRef().value(), approved.amount(), approved.currency());
            case PgClient.Unknown unknown -> {
                evidence.put("lateApproval", "Unknown");
                evidence.put("cause", unknown.cause().name());
            }
        }
        return write(evidence);
    }

    private static void approval(Map<String, Object> evidence, String key, String kind, String pgTxId, BigDecimal amount,
            String currency) {
        evidence.put(key, kind);
        evidence.put("pgTxId", pgTxId);
        evidence.put("amount", plain(amount));
        evidence.put("currency", currency);
    }

    private static String plain(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private static String write(Map<String, Object> evidence) {
        return JSON.writeValueAsString(evidence);
    }
}
