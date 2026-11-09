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
import org.springframework.http.HttpHeaders;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.function.Function;

/**
 * 토스페이먼츠 결제 API를 {@link PgClient} 포트로 옮기는 어댑터(ADR 0023, pg-integration.md 6절).
 * 구매자가 결제창에서 받은 {@code paymentKey}(clientPaymentRef)를 서버가 {@code /v1/payments/confirm}으로 승인한다.
 * 금액·통화·승인 시각이 없거나 정수 금액이 아니면 값을 지어내지 않고 Unknown(PG_ERROR)로 둔다.
 *
 * <p>Note: 이 어댑터의 승인은 결제 행당 한 번이고 재확인은 조회만 쓴다. 토스 confirm의 멱등 지원 여부가
 * 확인되지 않아도 이중 승인(INV-1)은 막힌다. 토스는 늦은 승인을 막는 별도 API가 없어
 * {@link #preventLateApproval}이 Prevented를 내지 않고, 빌링(자동결제)은 지원하지 않는다.
 * 시크릿 키는 {@code Authorization} 헤더로만 보내고 로그·예외 메시지에 남기지 않는다.
 */
public class TossPgAdapter implements PgClient {
    private static final Logger log = LoggerFactory.getLogger(TossPgAdapter.class);
    // Note: 금액이 double로 파싱되면 49999.99999999999999 같은 값이 50000으로 보인다(INV-3)
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private static final String CANCEL_REASON = "예매 취소에 따른 환불";

    private final RestClient client;

    TossPgAdapter(RestClient client) {
        this.client = client;
    }

    static TossPgAdapter create(String baseUrl, String secretKey, Duration connectTimeout, Duration readTimeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        // Note: 토스 인증은 "시크릿 키 + ':'"을 Base64로 인코딩한 Basic 값이다
        var basic = Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        return new TossPgAdapter(RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic).build());
    }

    @Override
    public ApproveResult approve(ApproveCommand command) {
        var paymentKey = command.clientPaymentRef();
        if (paymentKey == null) {
            // 결제창을 거치지 않은 요청은 토스가 승인할 수 없다. 호출하지 않았으므로 우리 쪽 문제다
            log.warn("토스 승인에 필요한 clientPaymentRef가 없어 호출하지 않았다");
            return new Unknown(UnknownCause.CLIENT_ERROR);
        }
        var body = Map.of("paymentKey", paymentKey.value(), "orderId", command.orderId().value().toString(),
                "amount", command.amount().longValueExact());
        return this.<ApproveResult>call("approve",
                () -> post("/v1/payments/confirm", command.idempotencyKey().value(), body), response -> {
                    if (response.isSuccess()) return toApproveResult(donePayment(response, paymentKey));
                    return approveFailure(response);
                }, Unknown::new);
    }

    // 토스는 빌링(자동결제)을 이번 범위에서 지원하지 않는다. 보내지 않았으므로 확실히 승인되지 않았다
    @Override
    public ApproveResult approveBilling(ApproveCommand command, String billingKey) {
        log.warn("토스 모드는 빌링 승인을 지원하지 않는다");
        return new Declined(DeclineReason.NOT_SENT);
    }

    @Override
    public BillingKeyResult issueBillingKey(String authKey, String customerKey) {
        log.warn("토스 모드는 빌링키 발급을 지원하지 않는다");
        return new Unknown(UnknownCause.CLIENT_ERROR);
    }

    @Override
    public PaymentLookupResult findPayment(PgOrderId orderId) {
        return this.<PaymentLookupResult>call("findPayment",
                () -> get("/v1/payments/orders/{orderId}", orderId.value().toString()), response -> {
                    if (response.isNoSuchPayment()) return new NotFound();
                    if (!response.isSuccess()) return failure(response);
                    var node = response.json();
                    if (!orderId.value().toString().equals(text(node, "orderId"))) {
                        log.warn("토스 조회 응답의 주문 식별자가 요청과 다르다");
                        return new Unknown(UnknownCause.PG_ERROR);
                    }
                    return lookupResult(node);
                }, Unknown::new);
    }

    /**
     * 토스에는 아직 승인되지 않은 주문의 승인을 막는 API가 없다. DONE이면 승인 사실을 알리고 나머지는 모두 Unknown이다.
     * 우리가 승인(confirm)을 다시 부르지 않으므로 실제 늦은 승인은 없지만, 포트는 보장하지 못한다고 답한다.
     */
    @Override
    public LateApprovalResult preventLateApproval(PgOrderId orderId) {
        return switch (findPayment(orderId)) {
            case Approved approved -> new AlreadyApproved(approved.paymentRef(), approved.amount(),
                    approved.currency(), approved.approvedAt());
            case Unknown unknown -> unknown;
            case NotFound notFound -> new Unknown(UnknownCause.PROCESSING);
            case Declined declined -> new Unknown(UnknownCause.PROCESSING);
        };
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        var body = Map.of("cancelReason", CANCEL_REASON, "cancelAmount", command.amount().longValueExact());
        return this.<RefundResult>call("refund",
                () -> post("/v1/payments/{paymentKey}/cancel", command.idempotencyKey().value(), body,
                        command.paymentRef().value()),
                response -> {
                    if (response.isSuccess()) return toRefundResult(cancelResult(response.json(), command.paymentRef()));
                    return refundFailure(response);
                }, Unknown::new);
    }

    @Override
    public RefundLookupResult findRefund(PgRefundId refundId, PgPaymentRef paymentRef) {
        return this.<RefundLookupResult>call("findRefund", () -> get("/v1/payments/{paymentKey}", paymentRef.value()),
                response -> {
                    if (response.isNoSuchPayment()) return new NotFound();
                    if (!response.isSuccess()) return failure(response);
                    return cancelResult(response.json(), paymentRef);
                }, Unknown::new);
    }

    // 승인 응답: 요청한 paymentKey의 DONE 결제만 승인으로 받는다
    private PaymentLookupResult donePayment(PgResponse response, PgPaymentRef requested) {
        var node = response.json();
        if (!"DONE".equals(text(node, "status"))) {
            log.warn("토스 승인 응답의 상태가 DONE이 아니다 status={}", text(node, "status"));
            return new Unknown(UnknownCause.PG_ERROR);
        }
        if (!requested.value().equals(text(node, "paymentKey"))) {
            log.warn("토스 승인 응답의 paymentKey가 요청과 다르다");
            return new Unknown(UnknownCause.PG_ERROR);
        }
        var approved = approved(node);
        return approved == null ? new Unknown(UnknownCause.PG_ERROR) : approved;
    }

    private PaymentLookupResult lookupResult(JsonNode node) {
        var status = text(node, "status");
        if (status == null) return new Unknown(UnknownCause.PG_ERROR);
        return switch (status) {
            case "DONE" -> {
                var approved = approved(node);
                yield approved == null ? new Unknown(UnknownCause.PG_ERROR) : approved;
            }
            case "ABORTED" -> {
                var code = text(node.path("failure"), "code");
                log.info("토스 승인 실패 조회 failureCode={}", code);
                yield new Declined(declineReason(code));
            }
            // 유효 시간이 지나 승인할 수 없게 됐다. 승인된 적이 없다
            case "EXPIRED" -> new NotFound();
            case "IN_PROGRESS", "READY", "WAITING_FOR_DEPOSIT" -> new Unknown(UnknownCause.PROCESSING);
            case "CANCELED", "PARTIAL_CANCELED" -> {
                // 승인 뒤 취소된 결제다. 재확인이 이 상태를 보면 사람이 확인해야 한다
                log.warn("토스 조회에서 취소된 결제를 만났다 status={}", status);
                yield new Unknown(UnknownCause.PG_ERROR);
            }
            default -> {
                log.warn("토스 조회 응답의 상태를 알 수 없다 status={}", status);
                yield new Unknown(UnknownCause.PG_ERROR);
            }
        };
    }

    // 승인 호출은 NotFound를 만들지 않는다(없는 결제 세션은 Declined로 따로 해석한다)
    private static ApproveResult toApproveResult(PaymentLookupResult result) {
        return switch (result) {
            case Approved approved -> approved;
            case Declined declined -> declined;
            case Unknown unknown -> unknown;
            case NotFound notFound -> new Unknown(UnknownCause.CLIENT_ERROR);
        };
    }

    private static RefundResult toRefundResult(RefundLookupResult result) {
        return switch (result) {
            case Refunded refunded -> refunded;
            case RefundRejected rejected -> rejected;
            case Unknown unknown -> unknown;
            case NotFound notFound -> new Unknown(UnknownCause.CLIENT_ERROR);
        };
    }

    // 취소 응답과 결제 조회가 같은 Payment 객체를 쓴다. 우리는 결제당 한 번만 전액 환불하므로 DONE 취소 건은 하나여야 한다
    private RefundLookupResult cancelResult(JsonNode node, PgPaymentRef expectedPaymentRef) {
        if (!expectedPaymentRef.value().equals(text(node, "paymentKey"))) {
            log.warn("토스 응답의 paymentKey가 요청과 다르다");
            return new Unknown(UnknownCause.PG_ERROR);
        }
        var cancels = node.path("cancels");
        var done = new ArrayList<JsonNode>();
        var pending = false;
        if (cancels.isArray()) {
            for (var cancel : cancels) {
                if ("DONE".equals(text(cancel, "cancelStatus"))) done.add(cancel);
                else pending = true;
            }
        }
        if (done.size() > 1) {
            log.warn("토스 결제에 완료된 취소 건이 여러 개다 count={}", done.size());
            return new Unknown(UnknownCause.PG_ERROR);
        }
        if (done.isEmpty()) return pending ? new Unknown(UnknownCause.PROCESSING) : new NotFound();
        var cancel = done.getFirst();
        var transactionKey = text(cancel, "transactionKey");
        var amount = amount(cancel, "cancelAmount");
        var currency = text(node, "currency");
        var canceledAt = instant(cancel, "canceledAt");
        if (transactionKey == null || amount == null || currency == null || canceledAt == null) {
            log.warn("토스 취소 응답에 필수 필드가 없거나 금액이 정수가 아니다");
            return new Unknown(UnknownCause.PG_ERROR);
        }
        return new Refunded(transactionKey, amount, currency, canceledAt);
    }

    // 승인 응답에서 paymentKey·금액·통화·승인 시각 중 하나라도 없으면 null이다
    private Approved approved(JsonNode node) {
        var paymentKey = text(node, "paymentKey");
        var amount = amount(node, "totalAmount");
        var currency = text(node, "currency");
        var approvedAt = instant(node, "approvedAt");
        if (paymentKey == null || amount == null || currency == null || approvedAt == null) {
            log.warn("토스 승인 응답에 필수 필드가 없거나 금액이 정수가 아니다");
            return null;
        }
        return new Approved(new PgPaymentRef(paymentKey), amount, currency, approvedAt);
    }

    // 승인 호출의 실패 응답. 결과를 확신할 수 없는 응답은 모두 Unknown이다
    private ApproveResult approveFailure(PgResponse response) {
        var code = response.code();
        if (response.status() == 409 || "ALREADY_PROCESSED_PAYMENT".equals(code)
                || "IDEMPOTENT_REQUEST_PROCESSING".equals(code)) {
            // 처리 중이거나 이미 처리됐다. 승인 여부는 재확인 조회가 확정한다
            return unexpected(response, UnknownCause.PROCESSING);
        }
        if (code != null && (code.equals("REJECT_CARD_COMPANY") || code.equals("REJECT_CARD_PAYMENT")
                || code.startsWith("EXCEED_MAX_") || code.equals("NOT_FOUND_PAYMENT_SESSION"))) {
            log.info("토스 승인 거절 code={}", code);
            return new Declined(declineReason(code));
        }
        return failure(response);
    }

    private Unknown failure(PgResponse response) {
        // PROVIDER_ERROR는 4xx로 오지만 카드사·간편결제사 쪽 일시 오류다
        if (response.status() >= 500 || "PROVIDER_ERROR".equals(response.code())) {
            return unexpected(response, UnknownCause.PG_ERROR);
        }
        if (response.status() == 409) return unexpected(response, UnknownCause.PROCESSING);
        return unexpected(response, UnknownCause.CLIENT_ERROR);
    }

    private RefundResult refundFailure(PgResponse response) {
        var code = response.code();
        if (code != null && (code.equals("ALREADY_CANCELED_PAYMENT") || code.startsWith("NOT_CANCELABLE_"))) {
            log.info("토스 환불 거절 code={}", code);
            return new RefundRejected(RefundRejectReason.NOT_REFUNDABLE);
        }
        return failure(response);
    }

    private Unknown unexpected(PgResponse response, UnknownCause cause) {
        log.warn("토스 예상하지 못한 응답 status={} code={} cause={}", response.status(), response.code(), cause);
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
            log.warn("토스 통신 실패 operation={} cause={}", operation, cause, exception);
            return onUnknown.apply(cause);
        } catch (RuntimeException exception) {
            // 요청이 PG에 닿았는지 알 수 없으므로 거절이 아니라 Unknown이다
            log.warn("토스 호출 중 예상하지 못한 예외 operation={}", operation, exception);
            return onUnknown.apply(UnknownCause.PG_ERROR);
        }
        try {
            return mapper.apply(response);
        } catch (RuntimeException exception) {
            log.warn("토스 응답을 해석하지 못했다 operation={} status={}", operation, response.status(), exception);
            return onUnknown.apply(UnknownCause.PG_ERROR);
        }
    }

    private PgResponse post(String path, String idempotencyKey, Object body, Object... variables) {
        var request = client.post().uri(path, variables).header("Idempotency-Key", idempotencyKey).body(body);
        return request.exchange((req, res) -> read(res.getStatusCode(), res.getBody().readAllBytes()));
    }

    private PgResponse get(String template, String variable) {
        return client.get().uri(template, variable)
                .exchange((req, res) -> read(res.getStatusCode(), res.getBody().readAllBytes()));
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
        if (code.equals("REJECT_CARD_COMPANY")) return DeclineReason.CARD_DECLINED;
        if (code.equals("REJECT_CARD_PAYMENT") || code.startsWith("EXCEED_MAX_")) return DeclineReason.INSUFFICIENT_LIMIT;
        return DeclineReason.OTHER;
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        return value == null || !value.isString() ? null : value.asString();
    }

    // KRW는 소수 자리가 없다. 정수가 아닌 금액은 NUMERIC(15,0) 저장 때 반올림되므로 해석 실패로 본다
    private static BigDecimal amount(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isNumber()) return null;
        var amount = value.decimalValue();
        return amount.stripTrailingZeros().scale() <= 0 ? amount.setScale(0) : null;
    }

    // 토스 시각은 +09:00 오프셋이 붙은 ISO 8601이다
    private static Instant instant(JsonNode node, String field) {
        var value = text(node, field);
        return value == null ? null : OffsetDateTime.parse(value).toInstant();
    }

    @FunctionalInterface
    private interface HttpCall {
        PgResponse execute();
    }

    private record PgResponse(int status, byte[] body) {
        boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        // 경로 오류 같은 404와 "해당 결제 없음"을 구분한다
        boolean isNoSuchPayment() {
            return status == 404 && "NOT_FOUND_PAYMENT".equals(code());
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
