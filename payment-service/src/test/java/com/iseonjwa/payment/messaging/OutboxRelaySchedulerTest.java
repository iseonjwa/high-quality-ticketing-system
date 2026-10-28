package com.iseonjwa.payment.messaging;

import com.iseonjwa.common.messaging.outbox.OutboxEventSender;
import com.iseonjwa.common.messaging.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Note: 스케줄러를 켠 컨텍스트가 캐시되어 남으면 다른 테스트의 outbox_events 행을 집어 가므로 끝나면 컨텍스트를 닫는다.
@DirtiesContext
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "messaging.outbox.relay-enabled=true", "messaging.outbox.interval=100ms"})
@ActiveProfiles("test")
class OutboxRelaySchedulerTest {
    @Autowired
    OutboxRepository outbox;
    @Autowired
    JdbcTemplate jdbc;

    // Note: 컨텍스트가 뜨자마자 스케줄러가 돌기 시작하므로 처음부터 동작하는 가짜 sender를 빈으로 둔다
    @TestConfiguration
    static class FakeSender {
        @Bean
        @Primary
        OutboxEventSender fakeSender() {
            return (topic, key, payload) -> CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void 스케줄러가_주기적으로_Outbox를_비운다() throws InterruptedException {
        jdbc.update("DELETE FROM outbox_events");
        outbox.insert(UUID.randomUUID(), "payment.result", "9001", "PaymentApproved", "{}", Instant.now());

        var deadline = System.nanoTime() + 5_000_000_000L;
        String status;
        do {
            status = jdbc.queryForObject("SELECT status FROM outbox_events WHERE message_key = '9001'", String.class);
            if (status.equals("SENT")) break;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);

        assertEquals("SENT", status);
    }
}
