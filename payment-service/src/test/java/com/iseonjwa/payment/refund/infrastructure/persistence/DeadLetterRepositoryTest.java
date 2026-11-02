package com.iseonjwa.payment.refund.infrastructure.persistence;

import com.iseonjwa.payment.refund.domain.DeadLetterRepository;
import com.iseonjwa.payment.refund.domain.DeadLetterResolution;
import com.iseonjwa.payment.refund.domain.DeadLetterStatus;
import com.iseonjwa.payment.refund.domain.NewDeadLetter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Note: 테스트 트랜잭션 안에서 쓰고 롤백한다. 기존 OPEN 행이 있어도 영향이 없도록 개수는 이 테스트의 키로만 확인한다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
@Transactional
class DeadLetterRepositoryTest {
    @Autowired
    DeadLetterRepository deadLetters;

    @Test
    void OPEN_행을_저장하고_event_key로_읽는다() {
        var eventId = UUID.randomUUID();
        var now = Instant.now();

        var id = deadLetters.insertOpen(new NewDeadLetter("payment.command", 3, 42L, "8800001", "ek-" + eventId, eventId,
                "RefundRequested", "{\"raw\":1}", "처리 중"), now);

        var saved = deadLetters.findByEventKey("ek-" + eventId).orElseThrow();
        assertEquals(id.orElseThrow(), saved.id());
        assertEquals("payment.command", saved.sourceTopic());
        assertEquals(3, saved.sourcePartition());
        assertEquals(42L, saved.sourceOffset());
        assertEquals("8800001", saved.messageKey());
        assertEquals(eventId, saved.eventId());
        assertEquals("RefundRequested", saved.eventType());
        assertEquals("{\"raw\":1}", saved.payloadRaw());
        assertEquals(DeadLetterStatus.OPEN, saved.status());
        assertNull(saved.resolution());
        assertNull(saved.refundId());
        assertEquals(now.toEpochMilli(), saved.createdAt().toEpochMilli());
        assertNull(saved.resolvedAt());
    }

    @Test
    void 같은_event_key는_저장하지_않고_빈_값을_돌려준다() {
        var deadLetter = new NewDeadLetter("t", 0, 1L, null, "ek-" + UUID.randomUUID(), null, null, "raw", "처리 중");

        assertTrue(deadLetters.insertOpen(deadLetter, Instant.now()).isPresent());
        assertTrue(deadLetters.insertOpen(deadLetter, Instant.now()).isEmpty());
    }

    @Test
    void OPEN_행의_사유를_바꾸고_닫으면_더_바꾸지_않는다() {
        var key = "ek-" + UUID.randomUUID();
        var id = deadLetters.insertOpen(new NewDeadLetter("t", 0, 1L, null, key, null, null, "raw", "처리 중"),
                Instant.now()).orElseThrow();

        assertTrue(deadLetters.updateOpenReason(id, "금액 불일치"));
        assertEquals("금액 불일치", deadLetters.findByEventKey(key).orElseThrow().reason());

        assertTrue(deadLetters.resolve(id, DeadLetterResolution.NO_OBLIGATION, null, Instant.now()));
        var resolved = deadLetters.findByEventKey(key).orElseThrow();
        assertEquals(DeadLetterStatus.RESOLVED, resolved.status());
        assertEquals(DeadLetterResolution.NO_OBLIGATION, resolved.resolution());

        assertFalse(deadLetters.resolve(id, DeadLetterResolution.DUPLICATE, null, Instant.now()));
        assertFalse(deadLetters.updateOpenReason(id, "다시"));
    }
}
