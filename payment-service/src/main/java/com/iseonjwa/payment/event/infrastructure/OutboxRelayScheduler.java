package com.iseonjwa.payment.event.infrastructure;

import com.iseonjwa.payment.event.application.OutboxRelayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payment.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelayService relay;

    public OutboxRelayScheduler(OutboxRelayService relay) {
        this.relay = relay;
    }

    // Note: fixedDelay라 이전 실행이 끝난 뒤에 간격을 센다. 실행기 분리(전용 스레드)는 ISE-40에서 한다.
    @Scheduled(fixedDelayString = "${payment.outbox.interval:500ms}")
    void relay() {
        try {
            relay.relayOnce();
        } catch (RuntimeException exception) {
            // 예외가 새면 이후 스케줄이 계속되더라도 원인이 사라진다. DB 장애 등은 다음 주기에 다시 시도한다
            log.error("Outbox 릴레이 실행 실패", exception);
        }
    }
}
