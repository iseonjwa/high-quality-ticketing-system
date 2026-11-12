package com.iseonjwa.mockpg.billing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BillingKeySignerTest {
    private final BillingKeySigner signer = new BillingKeySigner("secret-a");

    @Test
    void 발급한_키는_같은_secret의_새_인스턴스에서도_유효하다() {
        var billingKey = signer.issue();

        assertTrue(billingKey.startsWith("bk-"), billingKey);
        assertTrue(new BillingKeySigner("secret-a").isIssued(billingKey));
    }

    @Test
    void 다른_secret의_인스턴스에서는_유효하지_않다() {
        assertFalse(new BillingKeySigner("secret-b").isIssued(signer.issue()));
    }

    @Test
    void 서명_한_글자를_바꾼_키는_유효하지_않다() {
        var billingKey = signer.issue();
        var last = billingKey.charAt(billingKey.length() - 1);

        assertFalse(signer.isIssued(billingKey.substring(0, billingKey.length() - 1) + (last == 'A' ? 'B' : 'A')));
    }

    @Test
    void id를_바꾼_키는_유효하지_않다() {
        var billingKey = signer.issue();
        var signature = billingKey.substring(billingKey.lastIndexOf('.'));

        assertFalse(signer.isIssued("bk-" + UUID.randomUUID() + signature));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"bk-never-issued", "bk-.", "bk-abc.", "bk-.sig", "bk-8f14e45f-ceea-4a3b-9c2d-1b2c3d4e5f60"})
    void 형식이_틀리거나_발급하지_않은_키는_유효하지_않다(String billingKey) {
        assertFalse(signer.isIssued(billingKey));
    }

    @Test
    void 이전_형식의_키는_유효하지_않다() {
        assertFalse(signer.isIssued("bk-" + UUID.randomUUID()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void secret이_비어_있으면_만들_수_없다(String secret) {
        assertThrows(IllegalArgumentException.class, () -> new BillingKeySigner(secret));
    }
}
