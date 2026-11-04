package com.iseonjwa.payment.billing.infrastructure.crypto;

import com.iseonjwa.payment.billing.application.BillingKeyEncryptor;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 빌링키 암호화(ADR 0042). AES-256-GCM으로 암호화하고 Base64(IV 12바이트 ‖ 암호문 ‖ 태그 16바이트)로 저장한다.
 * GCM 태그가 맞지 않으면(값이 바뀌었거나 다른 키) 복호화가 실패한다.
 *
 * <p>Note: 키 문자열은 다른 비밀값과 같은 소문자 16진수 64자지만, 결제 토큰 키(ADR 0025)와 달리 16진수로 디코딩한 32바이트를
 * 쓴다. HMAC은 키 길이가 자유롭지만 AES-256은 정확히 32바이트여야 하기 때문이다. 오류 메시지에 키 값을 넣지 않는다.
 */
public final class AesGcmBillingKeyEncryptor implements BillingKeyEncryptor {
    static final int SECRET_LENGTH = 64;
    private static final Pattern LOWERCASE_HEX = Pattern.compile("[0-9a-f]+");
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    private AesGcmBillingKeyEncryptor(byte[] keyBytes) {
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    // 조건에 어긋나면 IllegalStateException을 던져 서비스 기동을 실패시킨다(fail-fast)
    public static AesGcmBillingKeyEncryptor from(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("BILLING_KEY_SECRET이 설정되지 않았습니다");
        }
        if (secret.length() != SECRET_LENGTH) {
            throw new IllegalStateException("BILLING_KEY_SECRET은 " + SECRET_LENGTH + "자여야 합니다");
        }
        if (!LOWERCASE_HEX.matcher(secret).matches()) {
            throw new IllegalStateException("BILLING_KEY_SECRET은 소문자 16진수여야 합니다");
        }
        return new AesGcmBillingKeyEncryptor(HexFormat.of().parseHex(secret));
    }

    @Override
    public String encrypt(String plain) {
        var iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            var encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(IV_LENGTH + encrypted.length)
                    .put(iv).put(encrypted).array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("빌링키를 암호화하지 못했습니다", exception);
        }
    }

    @Override
    public String decrypt(String stored) {
        var bytes = Base64.getDecoder().decode(stored);
        if (bytes.length <= IV_LENGTH) {
            throw new IllegalStateException("저장된 빌링키 형식이 올바르지 않습니다");
        }
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_LENGTH));
            return new String(cipher.doFinal(bytes, IV_LENGTH, bytes.length - IV_LENGTH), StandardCharsets.UTF_8);
        } catch (AEADBadTagException exception) {
            throw new IllegalStateException("빌링키 암호문이 바뀌었거나 다른 키로 암호화됐습니다", exception);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("빌링키를 복호화하지 못했습니다", exception);
        }
    }

    @Override
    public String toString() {
        return "AesGcmBillingKeyEncryptor[REDACTED]";
    }
}
