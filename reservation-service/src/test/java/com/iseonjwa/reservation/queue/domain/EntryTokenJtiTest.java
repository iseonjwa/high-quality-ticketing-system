package com.iseonjwa.reservation.queue.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 입장 토큰 jti 공식이 계약(docs/design/queue/contracts.md)대로인지 확인한다. 발급기와 대기열 정리가 같은 공식을 쓴다 (ISE-207)
class EntryTokenJtiTest {

    @Test
    void 계약의_공식대로_SHA_256_앞_16바이트를_소문자_hex로_만든다() {
        // sha256("entry:31:42:1793176230123")의 앞 32자
        assertEquals("a49ade9e65c29e89faf71eae9bbd2eeb", EntryTokenJti.of(31L, 42L, 1_793_176_230_123L));
    }

    @Test
    void 항상_소문자_hex_32자다() {
        assertTrue(EntryTokenJti.of(1L, 2L, 3L).matches("[0-9a-f]{32}"));
    }

    @Test
    void 회차_사용자_발급_시각_중_하나만_달라도_다르다() {
        String base = EntryTokenJti.of(31L, 42L, 1_000L);

        assertNotEquals(base, EntryTokenJti.of(32L, 42L, 1_000L));
        assertNotEquals(base, EntryTokenJti.of(31L, 43L, 1_000L));
        assertNotEquals(base, EntryTokenJti.of(31L, 42L, 1_001L));
    }
}
