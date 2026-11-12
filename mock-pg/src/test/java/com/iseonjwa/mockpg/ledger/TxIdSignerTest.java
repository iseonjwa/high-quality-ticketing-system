package com.iseonjwa.mockpg.ledger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TxIdSignerTest {
    private final TxIdSigner signer = new TxIdSigner("secret-a");

    @Test
    void 발급한_거래_ID는_같은_secret의_새_인스턴스에서도_금액과_통화를_돌려준다() {
        var pgTxId = signer.issue(new BigDecimal("50000"), "KRW");

        assertTrue(pgTxId.startsWith("pg-"), pgTxId);
        assertEquals(Optional.of(new TxIdSigner.Verified(new BigDecimal("50000"), "KRW")),
                new TxIdSigner("secret-a").verify(pgTxId));
    }

    @Test
    void 소수점_금액도_담는다() {
        var verified = signer.verify(signer.issue(new BigDecimal("49999.5"), "KRW")).orElseThrow();

        assertEquals(0, new BigDecimal("49999.5").compareTo(verified.amount()));
    }

    @Test
    void 같은_금액은_표현이_달라도_같은_형식으로_서명한다() {
        var plain = signer.issue(new BigDecimal("50000"), "KRW");
        var padded = signer.issue(new BigDecimal("50000.00"), "KRW");
        var scientific = signer.issue(new BigDecimal("5E+4"), "KRW");

        for (var pgTxId : new String[] {plain, padded, scientific}) {
            assertTrue(pgTxId.contains(".50000.KRW."), pgTxId);
        }
    }

    @Test
    void 길이는_결제_서비스의_pg_tx_id_컬럼_200자_안이다() {
        assertTrue(signer.issue(new BigDecimal("999999999999"), "KRW").length() < 200);
    }

    @Test
    void 금액_통화_UUID_서명_한_글자를_바꾸면_실패한다() {
        var pgTxId = signer.issue(new BigDecimal("50000"), "KRW");
        var parts = pgTxId.substring(3).split("\\.");
        var lastSignature = parts[3];
        var flipped = lastSignature.substring(0, lastSignature.length() - 1) + (lastSignature.endsWith("A") ? "B" : "A");

        assertEquals(Optional.empty(), signer.verify("pg-" + parts[0] + ".50001.KRW." + parts[3]));
        assertEquals(Optional.empty(), signer.verify("pg-" + parts[0] + ".50000.USD." + parts[3]));
        assertEquals(Optional.empty(), signer.verify("pg-" + UUID.randomUUID() + ".50000.KRW." + parts[3]));
        assertEquals(Optional.empty(), signer.verify("pg-" + parts[0] + ".50000.KRW." + flipped));
    }

    @Test
    void 서명_없는_옛_형식은_실패한다() {
        assertEquals(Optional.empty(), signer.verify("pg-" + UUID.randomUUID()));
    }

    @Test
    void 다른_secret으로_만든_서명은_실패한다() {
        assertEquals(Optional.empty(), new TxIdSigner("secret-b").verify(signer.issue(new BigDecimal("50000"), "KRW")));
    }

    @Test
    void 파생하지_않은_secret_그대로의_서명은_실패한다_용도_분리() throws Exception {
        // 빌링키 서명은 HmacSHA256(secret, id)이다. 같은 방식으로 거래 ID 내용에 서명한 값을 돌려써도 통과하지 못해야 한다
        var payload = UUID.randomUUID() + ".50000.KRW";
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret-a".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var rawSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));

        assertEquals(Optional.empty(), signer.verify("pg-" + payload + "." + rawSignature));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"pg-", "pg-.", "pg-a.b", "pg-a.b.c", "pg-a.b.c.", "pg-a..KRW.sig", "bk-abc.50000.KRW.sig", "tx-1.50000.KRW.sig",
            "pg-없는-결제", "pg-tx-1"})
    void 형식이_틀린_값은_실패한다(String pgTxId) {
        assertEquals(Optional.empty(), signer.verify(pgTxId));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void secret이_비어_있으면_만들_수_없다(String secret) {
        assertThrows(IllegalArgumentException.class, () -> new TxIdSigner(secret));
    }
}
