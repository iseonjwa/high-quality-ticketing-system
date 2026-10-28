package com.iseonjwa.payment.messaging;

import com.iseonjwa.common.messaging.outbox.OutboxEventSender;
import com.iseonjwa.common.messaging.outbox.OutboxRelay;
import com.iseonjwa.common.messaging.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Note: common.messaging의 릴레이는 PostgreSQL 문법(SKIP LOCKED, RETURNING)을 쓰므로 PostgreSQL이 있는
// 서비스 쪽에서 검증한다(common의 테스트 DB는 H2). 릴레이는 테이블 전체에서 행을 집어 매 테스트 전에 outbox를 비운다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class OutboxRelayIntegrationTest {
    @Autowired
    OutboxRelay relay;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoSpyBean
    OutboxRepository outbox;
    @MockitoBean
    OutboxEventSender sender;

    @BeforeEach
    void clean() {
        reset(sender, outbox);
        when(sender.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        jdbc.update("DELETE FROM outbox");
    }

    @Test
    void 대기_중인_이벤트를_보내고_브로커_확인_뒤_SENT로_바꾼다() {
        add("100", "PaymentApproved");

        assertEquals(new OutboxRelay.Result(1, 1), relay.relayOnce());

        verify(sender).send(eq("payment.result"), eq("100"), any());
        assertEquals("SENT", status("100"));
        assertNotNull(jdbc.queryForObject("SELECT sent_at FROM outbox WHERE message_key = '100'", Instant.class));
    }

    @Test
    void 이미_보낸_이벤트는_다시_보내지_않는다() {
        add("100", "PaymentApproved");
        relay.relayOnce();

        assertEquals(0, relay.relayOnce().claimed());

        verify(sender, times(1)).send(any(), any(), any());
    }

    @Test
    void 브로커가_실패를_응답하면_PENDING으로_남기고_실패를_기록한다() {
        add("100", "PaymentApproved");
        when(sender.send(any(), any(), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("브로커 장애")));

        assertEquals(new OutboxRelay.Result(1, 0), relay.relayOnce());

        assertEquals("PENDING", status("100"));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM outbox WHERE message_key = '100'", Integer.class));
        assertEquals("브로커 장애", jdbc.queryForObject("SELECT last_error FROM outbox WHERE message_key = '100'", String.class));
    }

    @Test
    void 실패한_이벤트는_임대가_끝나기_전에는_다시_집지_않는다() {
        add("100", "PaymentApproved");
        when(sender.send(any(), any(), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("장애")));
        relay.relayOnce();

        relay.relayOnce();

        verify(sender, times(1)).send(any(), any(), any());
    }

    @Test
    void 임대가_끝나면_다시_보내고_성공하면_SENT가_된다() {
        add("100", "PaymentApproved");
        when(sender.send(any(), any(), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("장애")))
                .thenReturn(CompletableFuture.completedFuture(null));
        relay.relayOnce();
        expireLease();

        assertEquals(1, relay.relayOnce().sent());

        assertEquals("SENT", status("100"));
        verify(sender, times(2)).send(any(), any(), any());
    }

    @Test
    void 전송은_성공했는데_SENT_기록이_실패하면_같은_이벤트를_다시_보내고_뒤_이벤트는_계속_막힌다() {
        add("100", "PaymentApproved");
        add("100", "PaymentDeclined");
        doThrow(new IllegalStateException("DB 장애")).doCallRealMethod().when(outbox).markSent(anyLong(), any());

        assertThrows(IllegalStateException.class, () -> relay.relayOnce());
        expireLease();
        assertEquals(1, relay.relayOnce().sent(), "같은 이벤트가 다시 보내져 SENT가 된다");

        verify(sender, times(2)).send(any(), eq("100"), contains("PaymentApproved"));
        verify(sender, times(0)).send(any(), any(), contains("PaymentDeclined"));
    }

    @Test
    void 같은_키의_이벤트는_앞선_것이_SENT가_된_뒤에_보낸다() {
        add("100", "PaymentApproved");
        add("100", "PaymentDeclined");

        assertEquals(1, relay.relayOnce().claimed(), "한 번에 하나만 집는다");
        assertEquals(1, relay.relayOnce().claimed());

        var order = inOrder(sender);
        order.verify(sender).send(any(), eq("100"), contains("PaymentApproved"));
        order.verify(sender).send(any(), eq("100"), contains("PaymentDeclined"));
    }

    @Test
    void 앞선_이벤트의_전송이_실패하면_같은_키의_뒤_이벤트도_보내지_않는다() {
        add("100", "PaymentApproved");
        add("100", "PaymentDeclined");
        when(sender.send(any(), any(), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("장애")));

        relay.relayOnce();
        relay.relayOnce();

        verify(sender, times(1)).send(any(), any(), any());
    }

    @Test
    void 다른_키의_이벤트는_한_번에_함께_보낸다() {
        add("100", "PaymentApproved");
        add("200", "PaymentApproved");

        assertEquals(2, relay.relayOnce().sent());
    }

    @Test
    void 두_릴레이가_동시에_집어도_같은_행을_집지_못한다() throws Exception {
        for (var key = 1; key <= 6; key++) add(String.valueOf(key), "PaymentApproved");
        var now = Instant.now();
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                start.await();
                return outbox.claimNext(now, now.plusSeconds(30), 6);
            });
            var second = executor.submit(() -> {
                start.await();
                return outbox.claimNext(now, now.plusSeconds(30), 6);
            });
            start.countDown();
            var claimedByFirst = first.get(10, TimeUnit.SECONDS);
            var claimedBySecond = second.get(10, TimeUnit.SECONDS);

            var all = new java.util.HashSet<Long>();
            claimedByFirst.forEach(message -> assertTrue(all.add(message.id()), "중복으로 집힌 행"));
            claimedBySecond.forEach(message -> assertTrue(all.add(message.id()), "중복으로 집힌 행"));
            assertEquals(6, all.size(), "임대 덕분에 모든 행이 정확히 한 번씩 집힌다");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void SENT_기록은_이미_SENT인_행에_두_번_반영되지_않는다() {
        add("100", "PaymentApproved");
        var message = outbox.claimNext(Instant.now(), Instant.now().plusSeconds(30), 10).get(0);

        assertTrue(outbox.markSent(message.id(), Instant.now()));
        assertFalse(outbox.markSent(message.id(), Instant.now()));
    }

    private void add(String key, String eventType) {
        outbox.insert(UUID.randomUUID(), "payment.result", key, eventType, "{\"eventType\":\"" + eventType + "\"}",
                Instant.now());
    }

    private void expireLease() {
        jdbc.update("UPDATE outbox SET claimed_until = now() - interval '1 second'");
    }

    private String status(String key) {
        return jdbc.queryForObject("SELECT status FROM outbox WHERE message_key = ?", String.class, key);
    }
}
