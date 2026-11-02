package com.iseonjwa.mockpg.fault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.iseonjwa.mockpg.fault.FaultTarget.APPROVE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 설정 검증(mock-pg.md 5절). 위반은 IllegalArgumentException이고 설정 API에서 400 INVALID_REQUEST가 된다.
 */
class FaultRuleTest {

    @Test
    void target과_mode는_필수다() {
        assertThrows(NullPointerException.class, () -> new FaultRule(null, FaultMode.NONE, null, null, null, null));
        assertThrows(NullPointerException.class, () -> new FaultRule(APPROVE, null, null, null, null, null));
    }

    @ParameterizedTest
    @CsvSource({
            "PAYMENT_LOOKUP, DROP_RESPONSE",
            "PAYMENT_LOOKUP, DECLINE",
            "CANCEL, DECLINE"
    })
    void 걸_수_없는_조합은_거절한다(FaultTarget target, FaultMode mode) {
        var thrown = assertThrows(IllegalArgumentException.class, () -> new FaultRule(target, mode, null, null, null, null));

        assertTrue(thrown.getMessage().contains(mode.name()) && thrown.getMessage().contains(target.name()), thrown.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
            "APPROVE, DECLINE", "APPROVE, DROP_RESPONSE", "CANCEL, DROP_RESPONSE",
            "APPROVE, PROCESSING", "PAYMENT_LOOKUP, PROCESSING", "CANCEL, PROCESSING",
            "APPROVE, SERVER_ERROR", "PAYMENT_LOOKUP, SERVER_ERROR", "CANCEL, SERVER_ERROR",
            "APPROVE, NONE", "PAYMENT_LOOKUP, NONE", "CANCEL, NONE"
    })
    void 걸_수_있는_조합은_받는다(FaultTarget target, FaultMode mode) {
        assertDoesNotThrow(() -> new FaultRule(target, mode, null, null, null, null));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 300, 60_000})
    void DELAY의_delayMs_경계_안은_받는다(long delayMs) {
        assertEquals(delayMs, new FaultRule(APPROVE, FaultMode.DELAY, delayMs, null, null, null).delayMs());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 60_001})
    void DELAY의_delayMs_경계_밖은_거절한다(long delayMs) {
        assertThrows(IllegalArgumentException.class, () -> new FaultRule(APPROVE, FaultMode.DELAY, delayMs, null, null, null));
    }

    @Test
    void DELAY에_delayMs가_없으면_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> new FaultRule(APPROVE, FaultMode.DELAY, null, null, null, null));
    }

    @Test
    void DELAY가_아닌데_delayMs가_있으면_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> new FaultRule(APPROVE, FaultMode.PROCESSING, 100L, null, null, null));
    }

    @Test
    void rate를_생략하면_1이다() {
        assertEquals(1.0, new FaultRule(APPROVE, FaultMode.PROCESSING, null, null, null, null).rate());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.5, 1.0})
    void rate_경계_안은_받는다(double rate) {
        assertEquals(rate, new FaultRule(APPROVE, FaultMode.PROCESSING, null, rate, null, null).rate());
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.01, 1.01, Double.NaN})
    void rate_경계_밖은_거절한다(double rate) {
        assertThrows(IllegalArgumentException.class, () -> new FaultRule(APPROVE, FaultMode.PROCESSING, null, rate, null, null));
    }

    @Test
    void count는_null이거나_1_이상이다() {
        assertNull(new FaultRule(APPROVE, FaultMode.PROCESSING, null, null, null, null).count());
        assertEquals(1, new FaultRule(APPROVE, FaultMode.PROCESSING, null, null, 1, null).count());
        assertThrows(IllegalArgumentException.class, () -> new FaultRule(APPROVE, FaultMode.PROCESSING, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new FaultRule(APPROVE, FaultMode.PROCESSING, null, null, -1, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void orderId가_공백이면_거절한다(String orderId) {
        assertThrows(IllegalArgumentException.class, () -> new FaultRule(APPROVE, FaultMode.PROCESSING, null, null, null, orderId));
    }

    @Test
    void orderId가_없으면_모든_주문에_일치한다() {
        var rule = new FaultRule(APPROVE, FaultMode.PROCESSING, null, null, null, null);

        assertTrue(rule.matches("o-1"));
        assertTrue(rule.matches("o-2"));
    }

    @Test
    void orderId가_있으면_그_주문에만_일치한다() {
        var rule = new FaultRule(APPROVE, FaultMode.PROCESSING, null, null, null, "o-1");

        assertTrue(rule.matches("o-1"));
        assertFalse(rule.matches("o-2"));
    }
}
