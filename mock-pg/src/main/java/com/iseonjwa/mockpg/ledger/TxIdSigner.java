package com.iseonjwa.mockpg.ledger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * 승인 거래 ID({@code pgTxId})를 발급하고, 원장에 없는 거래의 환불이 오면 우리가 승인한 거래인지 확인한다. 형식은
 * {@code pg-<UUID>.<승인 금액>.<통화>.<서명>}이고 서명은 {@code HmacSHA256(거래 ID 키, "<UUID>.<금액>.<통화>")}의
 * URL-safe Base64(패딩 없음)이다. 금액과 통화가 서명 안에 있어서 원장을 잃은 뒤에도 승인 항목을 되살릴 수 있다. 원장은 메모리에만
 * 있어 다시 띄우면 사라지기 때문이다(mock-pg.md 2절). 실제 PG는 원장을 저장소에 둔다.
 *
 * <p>Note: 서명 키는 새 설정 없이 빌링키 서명 비밀값({@code mock-pg.billing.signing-secret})에서
 * {@code HmacSHA256(secret, "mock-pg/tx-id/v1")}로 파생한다. 같은 키를 빌링키와 거래 ID에 그대로 쓰면 한쪽 서명을 다른 쪽으로
 * 돌려쓸 수 있어서 용도마다 키를 나눈다(domain separation). 서명으로 알 수 있는 것은 "우리가 이 값을 발급했다"까지다.
 * 환불을 이미 했는지는 알 수 없어서 원장을 잃은 뒤의 중복 환불은 막지 못한다.
 */
@Component
public class TxIdSigner {
    /** 서명이 맞는 거래 ID에 담긴 승인 금액과 통화. */
    public record Verified(BigDecimal amount, String currency) {}

    private static final String PREFIX = "pg-";
    private static final String ALGORITHM = "HmacSHA256";
    private static final String KEY_PURPOSE = "mock-pg/tx-id/v1";

    private final byte[] key;

    TxIdSigner(@Value("${mock-pg.billing.signing-secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("mock-pg.billing.signing-secret이 필요합니다");
        }
        this.key = mac(secret.getBytes(StandardCharsets.UTF_8), KEY_PURPOSE);
    }

    /** 실제로 승인한 금액으로 거래 ID를 만든다. 장애 주입으로 요청 금액과 다를 수 있다. */
    public String issue(BigDecimal approvedAmount, String currency) {
        var payload = UUID.randomUUID() + "." + plain(approvedAmount) + "." + currency;
        return PREFIX + payload + "." + sign(payload);
    }

    /** 서명이 맞으면 담긴 금액·통화를, 서명이 없거나 형식이 틀리거나 서명이 다르면 빈 값을 돌려준다. */
    public Optional<Verified> verify(String pgTxId) {
        if (pgTxId == null || !pgTxId.startsWith(PREFIX)) return Optional.empty();
        var rest = pgTxId.substring(PREFIX.length());
        var signatureDot = rest.lastIndexOf('.');
        var currencyDot = signatureDot < 1 ? -1 : rest.lastIndexOf('.', signatureDot - 1);
        var uuidDot = rest.indexOf('.');
        // 금액에도 소수점이 있을 수 있어 점의 위치는 앞(UUID 뒤)과 뒤(통화·서명 앞)에서 각각 찾는다
        if (uuidDot < 1 || currencyDot <= uuidDot + 1 || signatureDot <= currencyDot + 1 || signatureDot == rest.length() - 1) {
            return Optional.empty();
        }
        var payload = rest.substring(0, signatureDot);
        var signature = rest.substring(signatureDot + 1);
        // Note: 서명을 비교하는 시간이 맞은 글자 수에 따라 달라지지 않도록 MessageDigest.isEqual로 비교한다
        if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Verified(new BigDecimal(rest.substring(uuidDot + 1, currencyDot)),
                    rest.substring(currencyDot + 1, signatureDot)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    // 같은 금액이 다른 문자열(50000, 50000.00)로 서명되지 않게 표현을 하나로 고정한다
    private static String plain(BigDecimal amount) {
        return amount.signum() == 0 ? "0" : amount.stripTrailingZeros().toPlainString();
    }

    private String sign(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac(key, payload));
    }

    private static byte[] mac(byte[] key, String data) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("거래 ID 서명을 만들지 못했습니다", e);
        }
    }
}
