package com.iseonjwa.payment.event.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Note: 업무 롤백과 처리 기록의 관계를 보려면 실제로 커밋·롤백돼야 해서 테스트 트랜잭션으로 감싸지 않는다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class ProcessedEventServiceTest {
    private static final String GROUP = "payment.command";

    @Autowired
    ProcessedEventService processedEvents;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM processed_event WHERE consumer_group LIKE 'test.%' OR consumer_group = ?", GROUP);
    }

    @Test
    void 처음_보는_이벤트만_업무를_실행한다() {
        var eventId = UUID.randomUUID();
        var runs = new AtomicInteger();

        assertTrue(processedEvents.processOnce(eventId, GROUP, runs::incrementAndGet));
        assertFalse(processedEvents.processOnce(eventId, GROUP, runs::incrementAndGet));

        assertEquals(1, runs.get());
    }

    @Test
    void 소비_그룹이_다르면_같은_이벤트도_각각_처리한다() {
        var eventId = UUID.randomUUID();
        var runs = new AtomicInteger();

        assertTrue(processedEvents.processOnce(eventId, "test.a", runs::incrementAndGet));
        assertTrue(processedEvents.processOnce(eventId, "test.b", runs::incrementAndGet));

        assertEquals(2, runs.get());
    }

    @Test
    void 업무가_실패하면_처리_기록도_남지_않아_재전달_때_다시_처리한다() {
        var eventId = UUID.randomUUID();

        assertThrows(IllegalStateException.class, () -> processedEvents.processOnce(eventId, GROUP, () -> {
            throw new IllegalStateException("업무 실패");
        }));

        var runs = new AtomicInteger();
        assertTrue(processedEvents.processOnce(eventId, GROUP, runs::incrementAndGet));
        assertEquals(1, runs.get());
    }
}
