package com.iseonjwa.mockpg.billing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

/**
 * 빌링키를 발급하고, 받은 빌링키가 우리가 발급한 것인지 확인한다. 형식은 {@code bk-<UUID>.<서명>}이다.
 * 저장하지 않고 서명으로 확인하므로 다시 띄워도 발급한 키가 유효하고 메모리도 늘지 않는다. 실제 PG는 빌링키를 저장소에 둔다.
 */
@Component
public class BillingKeySigner {
    private static final String PREFIX = "bk-";
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    BillingKeySigner(@Value("${mock-pg.billing.signing-secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("mock-pg.billing.signing-secret이 필요합니다");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    String issue() {
        var id = UUID.randomUUID().toString();
        return PREFIX + id + "." + sign(id);
    }

    public boolean isIssued(String billingKey) {
        if (billingKey == null || !billingKey.startsWith(PREFIX)) return false;
        var dot = billingKey.lastIndexOf('.');
        if (dot < 0) return false;
        var id = billingKey.substring(PREFIX.length(), dot);
        var signature = billingKey.substring(dot + 1);
        if (id.isEmpty() || signature.isEmpty()) return false;
        // Note: 서명을 비교하는 시간이 맞은 글자 수에 따라 달라지지 않도록 MessageDigest.isEqual로 비교한다
        return MessageDigest.isEqual(sign(id).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String id) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(id.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("빌링키 서명을 만들지 못했습니다", e);
        }
    }
}
