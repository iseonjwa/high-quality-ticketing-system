package com.iseonjwa.reservation.seathold.presentation.scheduling;

import com.iseonjwa.reservation.seathold.application.SeatHoldRequestProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 접수 뒤 30초가 지나도록 처리를 시작하지 못한 접수형 요청을 {@code REJECTED(TIMEOUT)}으로 닫는다(SA 10-5, ISE-17).
 * 소비자가 멈추거나 메시지가 사라져도 사용자가 폴링을 끝없이 하지 않게 한다. 늦게 온 메시지는 소비자에서 건너뛴다.
 *
 * <p>Note: 한 번에 batchSize건씩, 덜 찰 때까지 반복한다. 한 문장으로 모두 닫으면 그동안 많은 행 잠금을 오래 쥔다.
 */
@Component
@ConditionalOnProperty(prefix = "seat-hold.request-timeout", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class SeatHoldRequestTimeoutScheduler {
    private static final Logger log = LoggerFactory.getLogger(SeatHoldRequestTimeoutScheduler.class);
    // 소비자가 오래 멈춰 대상이 아주 많아도 한 주기가 끝없이 길어지지 않게 한다. 남은 건은 다음 주기에 닫는다
    private static final int MAX_ROUNDS = 20;

    private final SeatHoldRequestProcessor processor;
    private final int batchSize;

    public SeatHoldRequestTimeoutScheduler(SeatHoldRequestProcessor processor,
            @Value("${seat-hold.request-timeout.batch-size:500}") int batchSize) {
        this.processor = processor;
        this.batchSize = batchSize;
    }

    // fixedDelay라 앞 주기가 끝난 뒤 간격을 센다. 한 인스턴스 안에서 주기가 겹치지 않는다
    @Scheduled(fixedDelayString = "${seat-hold.request-timeout.interval:5s}",
            initialDelayString = "${seat-hold.request-timeout.interval:5s}")
    public void rejectTimedOut() {
        var total = 0;
        for (var round = 0; round < MAX_ROUNDS; round++) {
            var closed = processor.rejectTimedOut(batchSize);
            total += closed;
            if (closed < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("접수형 선점 요청 {}건을 시한 초과(TIMEOUT)로 닫았습니다", total);
        }
    }
}
