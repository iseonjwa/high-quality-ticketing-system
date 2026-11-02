package com.iseonjwa.payment.billing;

import com.iseonjwa.payment.billing.infrastructure.crypto.AesGcmBillingKeyEncryptor;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 빌링키 암호화(ADR 0042)
class AesGcmBillingKeyEncryptorTest {
    private static final String SECRET = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";
    private static final String OTHER_SECRET = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    private final AesGcmBillingKeyEncryptor encryptor = AesGcmBillingKeyEncryptor.from(SECRET);

    @Test
    void 암호화한_값을_복호화하면_원문이고_원문은_저장값에_드러나지_않는다() {
        var stored = encryptor.encrypt("bk-plain-billing-key");

        assertEquals("bk-plain-billing-key", encryptor.decrypt(stored));
        assertFalse(stored.contains("bk-plain"));
        assertTrue(stored.length() <= 512, "billing_key_enc VARCHAR(512)에 들어가야 한다");
    }

    // IV를 매번 새로 만들어 같은 원문도 저장값이 다르다. 같은 IV를 다시 쓰면 GCM의 기밀성이 깨진다
    @Test
    void 같은_원문도_암호화할_때마다_저장값이_다르다() {
        assertNotEquals(encryptor.encrypt("bk-same"), encryptor.encrypt("bk-same"));
    }

    @Test
    void 저장값이_바뀌었거나_다른_키면_복호화하지_않는다() {
        var stored = encryptor.encrypt("bk-plain");
        var bytes = Base64.getDecoder().decode(stored);
        bytes[bytes.length - 1] ^= 1;

        assertThrows(IllegalStateException.class,
                () -> encryptor.decrypt(Base64.getEncoder().encodeToString(bytes)));
        assertThrows(IllegalStateException.class, () -> AesGcmBillingKeyEncryptor.from(OTHER_SECRET).decrypt(stored));
    }

    @Test
    void 키가_없거나_형식이_틀리면_기동을_막고_메시지에_값을_넣지_않는다() {
        assertEquals("BILLING_KEY_SECRET이 설정되지 않았습니다",
                assertThrows(IllegalStateException.class, () -> AesGcmBillingKeyEncryptor.from("")).getMessage());
        assertEquals("BILLING_KEY_SECRET은 64자여야 합니다",
                assertThrows(IllegalStateException.class, () -> AesGcmBillingKeyEncryptor.from("abcd")).getMessage());
        var upper = SECRET.toUpperCase();
        var exception = assertThrows(IllegalStateException.class, () -> AesGcmBillingKeyEncryptor.from(upper));
        assertEquals("BILLING_KEY_SECRET은 소문자 16진수여야 합니다", exception.getMessage());
        assertFalse(exception.getMessage().contains(upper));
    }
}
