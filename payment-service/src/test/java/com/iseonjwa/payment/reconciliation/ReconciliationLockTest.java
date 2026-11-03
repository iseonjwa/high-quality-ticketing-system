package com.iseonjwa.payment.reconciliation;

import com.iseonjwa.payment.reconciliation.application.ReconciliationProperties;
import com.iseonjwa.payment.reconciliation.application.ReconciliationRecorder;
import com.iseonjwa.payment.reconciliation.domain.MismatchRepository;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationRule;
import com.iseonjwa.payment.reconciliation.domain.RuleCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 규칙별 advisory 잠금을 다른 인스턴스가 쥐고 있으면 그 규칙을 건너뛰고 아무것도 닫지 않는다. 잠금은 다른 연결이 쥐어야 해서
 * 테스트 트랜잭션을 쓰지 않고(커밋한다) 다른 스레드에서 대사를 돌린다. 규칙은 아무것도 찾지 않는 가짜라 잠금이 풀렸다면 {@code OPEN} 행을
 * 닫았을 것이다. 데이터는 다른 테스트와 겹치지 않는 ID를 쓰고 끝나면 지운다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class ReconciliationLockTest {
    private static final long PAYMENT_ID = 987_654_321L;

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReconciliationRecorder recorder;
    @Autowired
    ReconciliationProperties properties;
    @Autowired
    PlatformTransactionManager transactionManager;

    private final ReconciliationRule nothingFound = new ReconciliationRule() {
        @Override
        public RuleCode code() {
            return RuleCode.RC_7;
        }

        @Override
        public Evaluation evaluate(Instant now, com.iseonjwa.payment.reconciliation.domain.ReconciliationPolicy policy) {
            return new Evaluation(List.of(), Set.of());
        }
    };

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM reconciliation_mismatches WHERE payment_id = ?", PAYMENT_ID);
    }

    @Test
    void 다른_인스턴스가_같은_규칙의_잠금을_쥐고_있으면_건너뛰고_닫지_않는다() {
        insertOpen();
        var tx = new TransactionTemplate(transactionManager);

        var held = tx.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, MismatchRepository.lockKey(RuleCode.RC_7));
            return CompletableFuture.supplyAsync(
                    () -> recorder.reconcile(nothingFound, Instant.now(), properties.policy())).join();
        });

        assertTrue(held.skipped());
        assertEquals("OPEN", status());

        var free = recorder.reconcile(nothingFound, Instant.now(), properties.policy());
        assertFalse(free.skipped());
        assertEquals(1, free.resolved());
        assertEquals("RESOLVED", status());
    }

    @Test
    void 다른_규칙의_잠금은_서로_막지_않는다() {
        var tx = new TransactionTemplate(transactionManager);

        var result = tx.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, MismatchRepository.lockKey(RuleCode.RC_8));
            return CompletableFuture.supplyAsync(
                    () -> recorder.reconcile(nothingFound, Instant.now(), properties.policy())).join();
        });

        assertFalse(result.skipped());
    }

    private void insertOpen() {
        jdbc.update("""
                INSERT INTO reconciliation_mismatches (rule, reservation_id, payment_id, detail, status, first_detected_at,
                                                       last_seen_at)
                VALUES ('RC-7', 1, ?, '{}'::jsonb, 'OPEN', now(), now())""", PAYMENT_ID);
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM reconciliation_mismatches WHERE payment_id = ?", String.class,
                PAYMENT_ID);
    }
}
