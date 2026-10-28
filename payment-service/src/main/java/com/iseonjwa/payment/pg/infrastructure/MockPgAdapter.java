package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.payment.domain.DeclineReason;
import com.iseonjwa.payment.pg.application.ApproveCommand;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.application.PgPaymentRef;
import com.iseonjwa.payment.pg.application.PgRefundId;
import com.iseonjwa.payment.pg.application.RefundCommand;
import com.iseonjwa.payment.pg.application.RefundRejectReason;
import com.iseonjwa.payment.pg.application.UnknownCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

/**
 * SA 11-5의 Mock PG API를 {@link PgClient} 포트로 옮기는 어댑터. 응답에는 PG가 처리한 금액·통화가
 * 들어 있어야 한다(S-28). 없으면 값을 지어내지 않고 Unknown(PG_ERROR)로 둔다.
 */
public class MockPgAdapter implements PgClient {
    private static final Logger log = LoggerFactory.getLogger(MockPgAdapter.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient client;

    MockPgAdapter(RestClient client) {
        this.client = client;
    }

    static MockPgAdapter create(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        return new MockPgAdapter(RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build());
    }

    @Override
    public ApproveResult approve(ApproveCommand command) {
        var body = Map.of("orderId", command.orderId().value().toString(), "amount", command.amount(),
                "currency", command.currency());
        return call(() -> post("/mock-pg/payments", command.idempotencyKey().value(), body), response -> {
            if (response.status() == 409) {
                // 망취소된 주문. PG가 승인을 거부했으므로 승인되지 않았음이 분명하다
                return new Declined(DeclineReason.NOT_SENT);
            }
            return paymentResult(response, ApproveResult.class);
        }, ApproveResult.class);
    }

    @Override
    public PaymentLookupResult findPayment(PgOrderId orderId) {
        return call(() -> get("/mock-pg/payments/" + orderId.value()), response -> {
            if (response.status() == 404) return new NotFound();
            return paymentResult(response, PaymentLookupResult.class);
        }, PaymentLookupResult.class);
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        var body = Map.of("pgTxId", command.paymentRef().value(), "refundId", command.refundId().value().toString(),
                "amount", command.amount(), "currency", command.currency(), "reason", command.reason());
        return call(() -> post("/mock-pg/refunds", command.idempotencyKey().value(), body),
                response -> refundResult(response, RefundResult.class), RefundResult.class);
    }

    @Override
    public RefundLookupResult findRefund(PgRefundId refundId, PgPaymentRef paymentRef) {
        return call(() -> get("/mock-pg/refunds/" + refundId.value()), response -> {
            if (response.status() == 404) return new NotFound();
            return refundResult(response, RefundLookupResult.class);
        }, RefundLookupResult.class);
    }

    @Override
    public LateApprovalResult preventLateApproval(PgOrderId orderId) {
        return call(() -> post("/mock-pg/payments/" + orderId.value() + "/cancel", null, null), response -> {
            if (response.status() >= 500) return new Unknown(UnknownCause.PG_ERROR);
            if (response.status() >= 400) return new Unknown(UnknownCause.CLIENT_ERROR);
            var node = response.json();
            return switch (text(node, "status")) {
                case "CANCELED" -> new Prevented();
                case "APPROVED" -> {
                    var approved = approved(node);
                    yield approved == null ? new Unknown(UnknownCause.PG_ERROR)
                            : new AlreadyApproved(approved.paymentRef(), approved.amount(), approved.currency(),
                                    approved.approvedAt());
                }
                default -> new Unknown(UnknownCause.PG_ERROR);
            };
        }, LateApprovalResult.class);
    }

    // 승인·조회가 같은 응답 모양을 쓴다. 404는 호출한 쪽이 먼저 처리한다
    private <T> T paymentResult(PgResponse response, Class<T> type) {
        if (response.status() >= 500) return unknown(UnknownCause.PG_ERROR, type);
        if (response.status() == 202) return unknown(UnknownCause.PROCESSING, type);
        if (response.status() >= 400) return unknown(UnknownCause.CLIENT_ERROR, type);
        var node = response.json();
        return switch (text(node, "status")) {
            case "APPROVED" -> {
                var approved = approved(node);
                yield approved == null ? unknown(UnknownCause.PG_ERROR, type) : type.cast(approved);
            }
            case "DECLINED" -> type.cast(new Declined(declineReason(text(node, "declineCode"))));
            case "PROCESSING" -> unknown(UnknownCause.PROCESSING, type);
            default -> unknown(UnknownCause.PG_ERROR, type);
        };
    }

    private <T> T refundResult(PgResponse response, Class<T> type) {
        if (response.status() >= 500) return unknown(UnknownCause.PG_ERROR, type);
        if (response.status() == 202) return unknown(UnknownCause.PROCESSING, type);
        if (response.status() == 422) {
            return type.cast(new RefundRejected(refundRejectReason(text(response.json(), "code"))));
        }
        if (response.status() >= 400) return unknown(UnknownCause.CLIENT_ERROR, type);
        var node = response.json();
        return switch (text(node, "status")) {
            case "REFUNDED" -> {
                var amount = amount(node);
                var currency = text(node, "currency");
                var refundedAt = instant(node, "refundedAt");
                yield amount == null || currency == null || refundedAt == null ? unknown(UnknownCause.PG_ERROR, type)
                        : type.cast(new Refunded(text(node, "refundTxId"), amount, currency, refundedAt));
            }
            case "PROCESSING" -> unknown(UnknownCause.PROCESSING, type);
            default -> unknown(UnknownCause.PG_ERROR, type);
        };
    }

    // 승인 응답에서 PG 거래 식별자·금액·통화·승인 시각 중 하나라도 없으면 null이다
    private Approved approved(JsonNode node) {
        var txId = text(node, "pgTxId");
        var amount = amount(node);
        var currency = text(node, "currency");
        var approvedAt = instant(node, "approvedAt");
        if (txId == null || amount == null || currency == null || approvedAt == null) {
            log.warn("Mock PG 승인 응답에 필수 필드가 없다");
            return null;
        }
        return new Approved(new PgPaymentRef(txId), amount, currency, approvedAt);
    }

    // 통신 실패와 해석 실패를 모두 Unknown으로 바꾼다. 어떤 예외도 거절로 해석하지 않는다.
    private <T> T call(HttpCall httpCall, Function<PgResponse, T> mapper, Class<T> type) {
        PgResponse response;
        try {
            response = httpCall.execute();
        } catch (ResourceAccessException exception) {
            var cause = isTimeout(exception) ? UnknownCause.TIMEOUT : UnknownCause.CONNECTION_LOST;
            log.warn("Mock PG 통신 실패 cause={}", cause);
            return unknown(cause, type);
        }
        try {
            return mapper.apply(response);
        } catch (RuntimeException exception) {
            log.warn("Mock PG 응답을 해석하지 못했다 status={}", response.status(), exception);
            return unknown(UnknownCause.PG_ERROR, type);
        }
    }

    private PgResponse post(String path, String idempotencyKey, Object body) {
        var request = client.post().uri(path);
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        if (body != null) request.body(body);
        return request.exchange((req, res) -> read(res.getStatusCode(), res.getBody().readAllBytes()));
    }

    private PgResponse get(String path) {
        return client.get().uri(path).exchange((req, res) -> read(res.getStatusCode(), res.getBody().readAllBytes()));
    }

    private static PgResponse read(HttpStatusCode status, byte[] body) {
        return new PgResponse(status.value(), body);
    }

    private static boolean isTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T> T unknown(UnknownCause cause, Class<T> type) {
        return (T) new Unknown(cause);
    }

    private static DeclineReason declineReason(String code) {
        if (code == null) return DeclineReason.OTHER;
        return switch (code) {
            case "CARD_DECLINED" -> DeclineReason.CARD_DECLINED;
            case "INSUFFICIENT_LIMIT" -> DeclineReason.INSUFFICIENT_LIMIT;
            default -> DeclineReason.OTHER;
        };
    }

    private static RefundRejectReason refundRejectReason(String code) {
        return "NOT_REFUNDABLE".equals(code) ? RefundRejectReason.NOT_REFUNDABLE : RefundRejectReason.OTHER;
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static BigDecimal amount(JsonNode node) {
        var value = node.get("amount");
        return value == null || !value.isNumber() ? null : value.decimalValue();
    }

    private static Instant instant(JsonNode node, String field) {
        var value = text(node, field);
        return value == null ? null : Instant.parse(value);
    }

    @FunctionalInterface
    private interface HttpCall {
        PgResponse execute();
    }

    private record PgResponse(int status, byte[] body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
