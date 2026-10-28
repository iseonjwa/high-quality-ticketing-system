package com.iseonjwa.common.messaging.outbox;

import org.springframework.scheduling.annotation.Scheduled;

public class OutboxRelayScheduler {
    // 한 번에 가득 집어 모두 보냈으면 쉬지 않고 이어서 비운다. 한 주기에 비우는 상한을 둬 다른 예약 작업을 굶기지 않는다
    static final int MAX_ROUNDS = 10;

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    // Note: 예외는 잡지 않는다. Spring 스케줄러가 ERROR로 로그를 남기고 다음 주기를 계속 돈다.
    // 브로커가 죽어 일부라도 못 보냈으면 이어 가지 않는다. 라운드마다 마감까지 기다리며 스레드를 독점하기 때문이다.
    // TODO: 전용 실행기 분리는 ISE-40(지금은 기본 스케줄러 스레드를 다른 예약 작업과 공유한다)
    @Scheduled(fixedDelayString = "${messaging.outbox.interval:500ms}")
    void relay() {
        for (var round = 0; round < MAX_ROUNDS && !Thread.currentThread().isInterrupted(); round++) {
            var result = relay.relayOnce();
            if (result.claimed() < relay.batchSize() || result.sent() < result.claimed()) return;
        }
    }
}
