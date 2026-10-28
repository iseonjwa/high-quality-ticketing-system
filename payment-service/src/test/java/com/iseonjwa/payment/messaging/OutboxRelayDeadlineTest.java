package com.iseonjwa.payment.messaging;

import com.iseonjwa.common.messaging.outbox.OutboxProperties;
import com.iseonjwa.common.messaging.outbox.OutboxRelay;
import com.iseonjwa.common.messaging.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 한 실행이 전송 시한 안에 끝나는지(임대 ≥ 시한의 2배 전제)를 짧은 시한으로 확인한다
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class OutboxRelayDeadlineTest {
    private static final Duration SEND_TIMEOUT = Duration.ofMillis(500);

    @Autowired
    OutboxRepository outbox;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbox_events");
    }

    @Test
    void 브로커가_응답하지_않으면_시한에_포기하고_실패를_기록한다() {
        add("100");
        var relay = relay(sender((topic, key, payload) -> new CompletableFuture<>()));
        var started = System.nanoTime();

        var result = relay.relayOnce();

        assertEquals(new OutboxRelay.Result(1, 0), result);
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(SEND_TIMEOUT.multipliedBy(3)) < 0,
                "시한 안에 실행이 끝나야 한다");
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM outbox_events", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM outbox_events", Integer.class));
    }

    @Test
    void 마감을_넘기면_아직_보내지_않은_행의_임대를_바로_푼다() {
        add("100");
        add("200");
        add("300");
        // 호출 하나가 시한만큼 막혀 마감을 넘긴다
        var relay = relay(sender((topic, key, payload) -> {
            sleep(SEND_TIMEOUT.toMillis() + 100);
            return CompletableFuture.completedFuture(null);
        }));

        var result = relay.relayOnce();

        assertEquals(3, result.claimed());
        assertEquals(1, result.sent());
        var released = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE status = 'PENDING' AND claimed_until IS NULL",
                Integer.class);
        assertEquals(2, released, "보내지 못한 두 행은 임대가 풀려 다음 실행이 바로 집는다");
    }

    private OutboxRelay relay(com.iseonjwa.common.messaging.outbox.OutboxEventSender sender) {
        return new OutboxRelay(outbox, sender, Clock.systemUTC(),
                new OutboxProperties(20, SEND_TIMEOUT.multipliedBy(4), SEND_TIMEOUT));
    }

    private static com.iseonjwa.common.messaging.outbox.OutboxEventSender sender(
            com.iseonjwa.common.messaging.outbox.OutboxEventSender sender) {
        return sender;
    }

    private void add(String key) {
        outbox.insert(UUID.randomUUID(), "payment.result", key, "PaymentApproved", "{}", Instant.now());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
