package com.iseonjwa.payment.refund.infrastructure.messaging;

import com.iseonjwa.common.messaging.consumer.NonRetryableMessageException;
import com.iseonjwa.payment.payment.application.BillingPaymentCommand;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * {@code payment.command}의 추첨 자동결제 요청({@code BillingPaymentRequested}, ISE-92·89)을 읽는다. 같은 토픽에
 * {@code RefundRequested}도 오므로(D6 기준선) 먼저 {@code eventType}을 보고, 자동결제가 아니면 비어 있는 값을 돌려
 * 환불 파서에 넘긴다. 자동결제인데 형식이 틀리면 {@link NonRetryableMessageException}이다(DLQ).
 *
 * <p>Note: 쓰는 필드만 골라 읽고 모르는 필드는 무시한다(SA 10-4). 금액은 소수 없는 정수 숫자만 받는다(contracts.md 2절).
 */
@Component
class BillingPaymentRequestedMessageParser {
    static final String EVENT_TYPE = "BillingPaymentRequested";
    static final int VERSION = 1;
    static final String PRODUCER = "reservation-service";

    private final ObjectMapper mapper;

    BillingPaymentRequestedMessageParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // JSON이 아니거나 다른 eventType이면 비어 있다. 그 판단은 환불 파서가 영구 오류로 낸다
    Optional<BillingPaymentCommand> parseIfBilling(String value) {
        var root = readOrNull(value);
        if (root == null || !EVENT_TYPE.equals(text(root, "eventType"))) return Optional.empty();
        if (!integral(root, "version") || root.get("version").asInt() != VERSION) {
            throw invalid("지원하지 않는 version입니다");
        }
        if (!PRODUCER.equals(text(root, "producer"))) {
            throw invalid("producer가 " + PRODUCER + "가 아닙니다");
        }
        var payload = root.get("payload");
        if (payload == null || !payload.isObject()) throw invalid("payload가 없습니다");
        var amount = amount(payload);
        var currency = text(payload, "currency");
        if (!"KRW".equals(currency)) throw invalid("currency가 KRW가 아닙니다");
        return Optional.of(new BillingPaymentCommand(longValue(payload, "reservationId"), longValue(payload, "userId"),
                amount, currency));
    }

    private JsonNode readOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            var root = mapper.readTree(value);
            return root != null && root.isObject() ? root : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static BigDecimal amount(JsonNode payload) {
        if (!integral(payload, "amount")) throw invalid("amount가 정수가 아닙니다");
        var amount = new BigDecimal(payload.get("amount").bigIntegerValue());
        if (amount.signum() <= 0) throw invalid("amount는 0보다 커야 합니다");
        return amount;
    }

    private static long longValue(JsonNode node, String field) {
        if (!integral(node, field) || !node.get(field).canConvertToLong()) throw invalid(field + "가 정수가 아닙니다");
        var value = node.get(field).longValue();
        if (value <= 0) throw invalid(field + "는 양수여야 합니다");
        return value;
    }

    private static boolean integral(JsonNode node, String field) {
        var value = node.get(field);
        return value != null && value.isIntegralNumber();
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        return value == null || !value.isString() ? null : value.asString();
    }

    private static NonRetryableMessageException invalid(String message) {
        return new NonRetryableMessageException(EVENT_TYPE + " 봉투가 올바르지 않습니다: " + message);
    }
}
