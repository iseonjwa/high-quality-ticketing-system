package com.iseonjwa.payment.messaging;

import com.iseonjwa.common.messaging.outbox.OutboxEventSender;
import com.iseonjwa.common.messaging.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// Note: 스케줄러를 켠 컨텍스트가 캐시되어 남으면 다른 테스트의 outbox 행을 집어 가므로 끝나면 컨텍스트를 닫는다.
@DirtiesContext
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "messaging.outbox.relay-enabled=true", "messaging.outbox.interval=100ms"})
@ActiveProfiles("test")
class OutboxRelaySchedulerTest {
    @Autowired
    OutboxRepository outbox;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoBean
    OutboxEventSender sender;

    @Test
    void 스케줄러가_주기적으로_Outbox를_비운다() throws InterruptedException {
        when(sender.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        jdbc.update("DELETE FROM outbox");
        outbox.insert(UUID.randomUUID(), "payment.result", "9001", "PaymentApproved", "{}", Instant.now());

        var deadline = System.nanoTime() + 5_000_000_000L;
        String status;
        do {
            status = jdbc.queryForObject("SELECT status FROM outbox WHERE message_key = '9001'", String.class);
            if (status.equals("SENT")) break;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);

        assertEquals("SENT", status);
    }
}
