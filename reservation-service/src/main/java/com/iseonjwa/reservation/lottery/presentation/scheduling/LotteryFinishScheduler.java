package com.iseonjwa.reservation.lottery.presentation.scheduling;

import com.iseonjwa.reservation.lottery.application.LotteryFinishService;
import com.iseonjwa.reservation.lottery.observability.LotteryMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * 추첨 행 잠금과 조건부 UPDATE로 한 번만 반영되어 인스턴스마다 돌아도 안전한 종결 스케줄러(1분, details.md 4절).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "lottery.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class LotteryFinishScheduler {

    private final LotteryFinishService finishService;
    private final LotteryMetrics lotteryMetrics;

    public LotteryFinishScheduler(LotteryFinishService finishService, LotteryMetrics lotteryMetrics) {
        this.finishService = finishService;
        this.lotteryMetrics = lotteryMetrics;
    }

    // 한 추첨이 실패해도 다른 추첨의 종결은 계속하고, 실패한 추첨은 DRAWN으로 남아 다음 주기에 다시 시도된다
    @Scheduled(fixedDelayString = "${lottery.scheduler.finish-interval:1m}")
    public void finish() {
        for (Long lotteryId : finishService.findFinishDueIds()) {
            try {
                finishService.finish(lotteryId).ifPresent(returned -> {
                    log.info("추첨 종결: lotteryId={}, 추첨석 {}석 반환", lotteryId, returned);
                    lotteryMetrics.seatsReverted(returned);
                });
            } catch (RuntimeException exception) {
                log.error("추첨 종결 실패: lotteryId={}", lotteryId, exception);
                lotteryMetrics.jobFailed(LotteryMetrics.Job.FINISH);
            }
        }

        // 추첨이 모두 끝났는데 남은 추첨석이 0이 아니면 반환이 빠진 것이다(details.md 5절)
        lotteryMetrics.updateProgress(finishService.readProgress());
    }
}
