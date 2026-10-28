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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

/**
 * SA 11-5의 Mock PG API를 {@link PgClient} 포트로 옮기는 어댑터. 응답 모양은 pg-integration.md의
 * "Mock PG 프로토콜" 표를 따른다. 금액·통화가 없거나 정수 금액이 아니면 값을 지어내지 않고 Unknown(PG_ERROR)로 둔다(S-28).
 *
 * <p>Note: 결과를 확신할 수 없는 응답은 모두 Unknown이다. 상태 코드만 보고 거절·미발견으로 단정하지 않고
 * 프로토콜이 정한 본문 코드까지 확인한다(INV-8).
 */
public class MockPgAdapter implements PgClient {
    private static final Logger log = LoggerFactory.getLogger(MockPgAdapter.class);
    // Note: 금액이 double로 파싱되면 49999.99999999999999 같은 값이 50000으로 보인다(INV-3)
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

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
        return this.<ApproveResult>call("approve", () -> post("/mock-pg/payments", command.idempotencyKey().value(), body),
                response -> {
                    if (response.status() == 409) {
                        // 망취소된 주문만 거절이다. 다른 409는 처리 중 충돌일 수 있어 승인될 수 있다
                        if ("ORDER_CANCELED".equals(response.code())) return new Declined(DeclineReason.NOT_SENT);
                        return new Unknown(UnknownCause.PROCESSING);
                    }
                    return toApproveResult(paymentResult(response));
                }, Unknown::new);
    }

    @Override
    public PaymentLookupResult findPayment(PgOrderId orderId) {
        return this.<PaymentLookupResult>call("findPayment", () -> get("/mock-pg/payments/" + orderId.value()), response -> {
            if (response.isNotFound()) return new NotFound();
            return paymentResult(response);
        }, Unknown::new);
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        var body = Map.of("pgTxId", command.paymentRef().value(), "refundId", command.refundId().value().toString(),
                "amount", command.amount(), "currency", command.currency(), "reason", command.reason());
        return this.<RefundResult>call("refund", () -> post("/mock-pg/refunds", command.idempotencyKey().value(), body),
                response -> toRefundResult(refundResult(response, command.paymentRef())), Unknown::new);
    }

    @Override
    public RefundLookupResult findRefund(PgRefundId refundId, PgPaymentRef paymentRef) {
        return this.<RefundLookupResult>call("findRefund", () -> get("/mock-pg/refunds/" + refundId.value()), response -> {
            if (response.isNotFound()) return new NotFound();
            return refundResult(response, paymentRef);
        }, Unknown::new);
    }

    @Override
    public LateApprovalResult preventLateApproval(PgOrderId orderId) {
        return this.<LateApprovalResult>call("preventLateApproval", () -> post("/mock-pg/payments/" + orderId.value() + "/cancel", null, null),
                response -> {
                    // 승인이 처리 중이면 PG는 취소를 보장하지 못한다. Prevented로 단정하지 않는다
                    if (response.status() == 202) return new Unknown(UnknownCause.PROCESSING);
                    if (response.status() >= 500) return new Unknown(UnknownCause.PG_ERROR);
                    if (response.status() >= 400) return unexpected(response, UnknownCause.CLIENT_ERROR);
                    var node = response.json();
                    return switch (text(node, "status")) {
                        case "CANCELED" -> new Prevented();
                        case "APPROVED" -> {
                            var approved = approved(node);
                            yield approved == null ? new Unknown(UnknownCause.PG_ERROR)
                                    : new AlreadyApproved(approved.paymentRef(), approved.amount(),
                                            approved.currency(), approved.approvedAt());
                        }
                        case null, default -> new Unknown(UnknownCause.PG_ERROR);
                    };
                }, Unknown::new);
    }

    // 승인 호출은 404를 NotFound로 해석하지 않으므로(paymentResult가 CLIENT_ERROR로 둔다) NotFound는 오지 않는다
    private static ApproveResult toApproveResult(PaymentLookupResult result) {
        return switch (result) {
            case Approved approved -> approved;
            case Declined declined -> declined;
            case Unknown unknown -> unknown;
            case NotFound notFound -> new Unknown(UnknownCause.CLIENT_ERROR);
        };
    }

    // 환불 호출도 마찬가지로 NotFound를 만들지 않는다
    private static RefundResult toRefundResult(RefundLookupResult result) {
        return switch (result) {
            case Refunded refunded -> refunded;
            case RefundRejected rejected -> rejected;
            case Unknown unknown -> unknown;
            case NotFound notFound -> new Unknown(UnknownCause.CLIENT_ERROR);
        };
    }

    // 승인·조회가 같은 응답 모양을 쓴다. 404는 호출한 쪽이 먼저 처리한다
    private PaymentLookupResult paymentResult(PgResponse response) {
        if (response.status() >= 500) return unexpected(response, UnknownCause.PG_ERROR);
        if (response.status() == 202) return new Unknown(UnknownCause.PROCESSING);
        if (response.status() >= 400) return unexpected(response, UnknownCause.CLIENT_ERROR);
        var node = response.json();
        return switch (text(node, "status")) {
            case "APPROVED" -> {
                var approved = approved(node);
                yield approved == null ? new Unknown(UnknownCause.PG_ERROR) : approved;
            }
            case "DECLINED" -> {
                var code = text(node, "declineCode");
                log.info("Mock PG 거절 declineCode={}", code);
                yield new Declined(declineReason(code));
            }
            case "PROCESSING" -> new Unknown(UnknownCause.PROCESSING);
            case null, default -> new Unknown(UnknownCause.PG_ERROR);
        };
    }

    private RefundLookupResult refundResult(PgResponse response, PgPaymentRef expectedPaymentRef) {
        if (response.status() >= 500) return unexpected(response, UnknownCause.PG_ERROR);
        if (response.status() == 202) return new Unknown(UnknownCause.PROCESSING);
        if (response.status() >= 400 && response.status() != 422) return unexpected(response, UnknownCause.CLIENT_ERROR);
        var node = response.json();
        return switch (text(node, "status")) {
            case "REJECTED" -> {
                var code = text(node, "code");
                log.info("Mock PG 환불 거절 code={}", code);
                yield new RefundRejected(refundRejectReason(code));
            }
            case "REFUNDED" -> refunded(node, expectedPaymentRef);
            case "PROCESSING" -> new Unknown(UnknownCause.PROCESSING);
            case null, default -> new Unknown(UnknownCause.PG_ERROR);
        };
    }

    // 환불 응답은 요청한 결제의 것이어야 한다. 다른 결제의 환불을 완료로 받으면 환불 의무가 사라진다(INV-4)
    private RefundLookupResult refunded(JsonNode node, PgPaymentRef expectedPaymentRef) {
        var refundTxId = text(node, "refundTxId");
        var amount = amount(node);
        var currency = text(node, "currency");
        var refundedAt = instant(node, "refundedAt");
        if (refundTxId == null || amount == null || currency == null || refundedAt == null) {
            log.warn("Mock PG 환불 응답에 필수 필드가 없거나 금액이 정수가 아니다");
            return new Unknown(UnknownCause.PG_ERROR);
        }
        if (!expectedPaymentRef.value().equals(text(node, "pgTxId"))) {
            log.warn("Mock PG 환불 응답의 결제 식별자가 요청과 다르다");
            return new Unknown(UnknownCause.PG_ERROR);
        }
        return new Refunded(refundTxId, amount, currency, refundedAt);
    }

    // 승인 응답에서 PG 거래 식별자·금액·통화·승인 시각 중 하나라도 없으면 null이다
    private Approved approved(JsonNode node) {
        var txId = text(node, "pgTxId");
        var amount = amount(node);
        var currency = text(node, "currency");
        var approvedAt = instant(node, "approvedAt");
        if (txId == null || amount == null || currency == null || approvedAt == null) {
            log.warn("Mock PG 승인 응답에 필수 필드가 없거나 금액이 정수가 아니다");
            return null;
        }
        return new Approved(new PgPaymentRef(txId), amount, currency, approvedAt);
    }

    private Unknown unexpected(PgResponse response, UnknownCause cause) {
        log.warn("Mock PG 예상하지 못한 응답 status={} code={} cause={}", response.status(), response.code(), cause);
        return new Unknown(cause);
    }

    // 통신 실패와 해석 실패를 모두 Unknown으로 바꾼다. 어떤 예외도 거절로 해석하지 않는다.
    private <T> T call(String operation, HttpCall httpCall, Function<PgResponse, T> mapper,
            Function<UnknownCause, T> onUnknown) {
        PgResponse response;
        try {
            response = httpCall.execute();
        } catch (ResourceAccessException exception) {
            var cause = isResponseTimeout(exception) ? UnknownCause.TIMEOUT : UnknownCause.CONNECTION_LOST;
            log.warn("Mock PG 통신 실패 operation={} cause={}", operation, cause, exception);
            return onUnknown.apply(cause);
        } catch (RuntimeException exception) {
            // 요청이 PG에 닿았는지 알 수 없으므로 거절이 아니라 Unknown이다
            log.warn("Mock PG 호출 중 예상하지 못한 예외 operation={}", operation, exception);
            return onUnknown.apply(UnknownCause.PG_ERROR);
        }
        try {
            return mapper.apply(response);
        } catch (RuntimeException exception) {
            log.warn("Mock PG 응답을 해석하지 못했다 operation={} status={}", operation, response.status(), exception);
            return onUnknown.apply(UnknownCause.PG_ERROR);
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

    // 연결 시간 초과는 요청이 PG에 닿기 전이므로 응답 시간 초과(TIMEOUT)와 구분해 CONNECTION_LOST로 둔다
    private static boolean isResponseTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpConnectTimeoutException) return false;
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) return true;
        }
        return false;
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
        return value == null || !value.isString() ? null : value.asString();
    }

    // KRW는 소수 자리가 없다. 정수가 아닌 금액은 NUMERIC(15,0) 저장 때 반올림되므로 해석 실패로 본다
    private static BigDecimal amount(JsonNode node) {
        var value = node.get("amount");
        if (value == null || !value.isNumber()) return null;
        var amount = value.decimalValue();
        return amount.stripTrailingZeros().scale() <= 0 ? amount.setScale(0) : null;
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
        boolean isNotFound() {
            // 경로 오류 같은 404와 "해당 주문 없음"을 구분한다
            return status == 404 && "NOT_FOUND".equals(code());
        }

        String code() {
            try {
                return text(json(), "code");
            } catch (RuntimeException exception) {
                return null;
            }
        }

        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
