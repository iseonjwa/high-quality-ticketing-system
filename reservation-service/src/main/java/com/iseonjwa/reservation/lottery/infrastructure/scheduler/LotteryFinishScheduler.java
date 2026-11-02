package com.iseonjwa.reservation.lottery.infrastructure.scheduler;

import com.iseonjwa.reservation.lottery.application.LotteryFinishService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 종결 스케줄러(1분, details.md 4절). 인스턴스마다 돌아도 안전하다. 추첨 행 잠금과 조건부 UPDATE로 한 번만 반영된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "lottery.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class LotteryFinishScheduler {

    private final LotteryFinishService finishService;
    // 남은 추첨석 수. 진행 중인 추첨이 없는데 0이 아니면 반환이 빠진 것이다(details.md 5절)
    private final AtomicLong remainingLotterySeats;

    public LotteryFinishScheduler(LotteryFinishService finishService, MeterRegistry meterRegistry) {
        this.finishService = finishService;
        this.remainingLotterySeats = meterRegistry.gauge("lottery.seats.remaining", new AtomicLong());
    }

    // 한 추첨이 실패해도 다른 추첨의 종결은 계속한다. 실패한 추첨은 DRAWN으로 남아 다음 주기에 다시 시도된다
    @Scheduled(fixedDelayString = "${lottery.scheduler.finish-interval:1m}")
    public void finish() {
        for (var lotteryId : finishService.findFinishDueIds()) {
            try {
                finishService.finish(lotteryId).ifPresent(returned ->
                        log.info("추첨 종결: lotteryId={}, 추첨석 {}석 반환", lotteryId, returned));
            } catch (RuntimeException exception) {
                log.error("추첨 종결 실패: lotteryId={}", lotteryId, exception);
            }
        }
        remainingLotterySeats.set(finishService.countRemainingLotterySeats());
    }
}
