package com.iseonjwa.payment.event.application;

import com.iseonjwa.payment.event.domain.OutboxEvent;
import com.iseonjwa.payment.event.domain.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// Note: 릴레이는 테이블 전체에서 행을 집으므로 매 테스트 전에 outbox를 비운다(테스트 전용 DB).
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class OutboxRelayServiceTest {
    @Autowired
    OutboxRelayService relay;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoBean
    OutboxEventSender sender;

    @BeforeEach
    void clean() {
        reset(sender);
        jdbc.update("DELETE FROM outbox");
    }

    @Test
    void 대기_중인_이벤트를_보내고_SENT로_바꾼다() {
        add("100", "PaymentApproved");

        assertEquals(1, relay.relayOnce());

        verify(sender).send(eq("payment.result"), eq("100"), any());
        assertEquals("SENT", status("100"));
        assertNotNull(jdbc.queryForObject("SELECT sent_at FROM outbox WHERE message_key = '100'", Instant.class));
    }

    @Test
    void 이미_보낸_이벤트는_다시_보내지_않는다() {
        add("100", "PaymentApproved");
        relay.relayOnce();

        assertEquals(0, relay.relayOnce());

        verify(sender, times(1)).send(any(), any(), any());
    }

    @Test
    void 전송이_실패하면_PENDING으로_남기고_실패를_기록한다() {
        add("100", "PaymentApproved");
        doThrow(new OutboxSendException("브로커 장애", null)).when(sender).send(any(), any(), any());

        assertEquals(0, relay.relayOnce());

        assertEquals("PENDING", status("100"));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM outbox WHERE message_key = '100'", Integer.class));
        assertEquals("브로커 장애", jdbc.queryForObject("SELECT last_error FROM outbox WHERE message_key = '100'", String.class));
    }

    @Test
    void 실패한_이벤트는_임대가_끝나기_전에는_다시_집지_않는다() {
        add("100", "PaymentApproved");
        doThrow(new OutboxSendException("브로커 장애", null)).when(sender).send(any(), any(), any());
        relay.relayOnce();

        relay.relayOnce();

        verify(sender, times(1)).send(any(), any(), any());
    }

    @Test
    void 임대가_끝나면_다시_보내고_성공하면_SENT가_된다() {
        add("100", "PaymentApproved");
        doThrow(new OutboxSendException("브로커 장애", null)).doNothing().when(sender).send(any(), any(), any());
        relay.relayOnce();
        jdbc.update("UPDATE outbox SET claimed_until = now() - interval '1 second'");

        assertEquals(1, relay.relayOnce());

        assertEquals("SENT", status("100"));
        verify(sender, times(2)).send(any(), any(), any());
    }

    @Test
    void 같은_키의_이벤트는_앞선_것이_SENT가_된_뒤에_보낸다() {
        add("100", "PaymentApproved");
        add("100", "PaymentDeclined");

        assertEquals(1, relay.relayOnce(), "한 번에 하나만 집는다");
        assertEquals(1, relay.relayOnce());

        var order = org.mockito.Mockito.inOrder(sender);
        order.verify(sender).send(any(), eq("100"), org.mockito.ArgumentMatchers.contains("PaymentApproved"));
        order.verify(sender).send(any(), eq("100"), org.mockito.ArgumentMatchers.contains("PaymentDeclined"));
    }

    @Test
    void 앞선_이벤트의_전송이_실패하면_같은_키의_뒤_이벤트도_보내지_않는다() {
        add("100", "PaymentApproved");
        add("100", "PaymentDeclined");
        doThrow(new OutboxSendException("브로커 장애", null)).when(sender).send(any(), any(), any());

        relay.relayOnce();
        relay.relayOnce();

        verify(sender, times(1)).send(any(), any(), any());
    }

    @Test
    void 다른_키의_이벤트는_한_번에_함께_보낸다() {
        add("100", "PaymentApproved");
        add("200", "PaymentApproved");

        assertEquals(2, relay.relayOnce());
    }

    @Test
    void 임대_중인_행은_다른_릴레이가_집지_못한다() {
        add("100", "PaymentApproved");
        var now = Instant.now();

        var first = outbox.claimNext(now, now.plusSeconds(30), 10);
        var second = outbox.claimNext(now, now.plusSeconds(30), 10);

        assertEquals(1, first.size());
        assertEquals(0, second.size());
    }

    @Test
    void 이미_SENT인_행의_성공_기록은_두_번_반영되지_않는다() {
        add("100", "PaymentApproved");
        var event = outbox.claimNext(Instant.now(), Instant.now().plusSeconds(30), 10).get(0);

        assertEquals(true, outbox.markSent(event.getId(), Instant.now()));
        assertEquals(false, outbox.markSent(event.getId(), Instant.now()));
    }

    private void add(String key, String eventType) {
        outbox.save(OutboxEvent.pending(UUID.randomUUID(), "payment.result", key, eventType,
                "{\"eventType\":\"" + eventType + "\"}", Instant.now()));
    }

    private String status(String key) {
        return jdbc.queryForObject("SELECT status FROM outbox WHERE message_key = ?", String.class, key);
    }
}
