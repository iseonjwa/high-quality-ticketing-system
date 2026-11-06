package com.iseonjwa.reservation.lottery.presentation.scheduling;

import com.iseonjwa.reservation.lottery.application.LotterySuccessionService;
import com.iseonjwa.reservation.lottery.observability.LotteryMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 승계는 추첨 행 잠금으로, 마감은 조건부 UPDATE로 한 번만 반영되어 인스턴스마다 돌아도 안전한 승계 워커(1초)와 승계 마감(10초, details.md 4절).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "lottery.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class LotterySuccessionScheduler {

    private final LotterySuccessionService successionService;
    private final LotteryMetrics lotteryMetrics;

    public LotterySuccessionScheduler(LotterySuccessionService successionService, LotteryMetrics lotteryMetrics) {
        this.successionService = successionService;
        this.lotteryMetrics = lotteryMetrics;
    }

    // 한 추첨이 실패해도 다른 추첨의 승계는 계속한다
    @Scheduled(fixedDelayString = "${lottery.scheduler.succession-interval:1s}")
    public void succeed() {
        for (Long lotteryId : successionService.findSuccessionOpenIds()) {
            try {
                int promoted = successionService.succeed(lotteryId);
                if (promoted > 0) {
                    log.info("추첨 예비자 승계: lotteryId={}, 새 당첨 {}명", lotteryId, promoted);
                    lotteryMetrics.succeeded(promoted);
                }
            } catch (RuntimeException exception) {
                log.error("추첨 예비자 승계 실패: lotteryId={}", lotteryId, exception);
                lotteryMetrics.jobFailed(LotteryMetrics.Job.SUCCESSION);
            }
        }
    }

    @Scheduled(fixedDelayString = "${lottery.scheduler.succession-close-interval:10s}")
    public void closeExpiredWaitlists() {
        int closed = successionService.closeExpiredWaitlists();
        if (closed > 0) {
            log.info("추첨 승계 마감: 예비자 {}명 LOST", closed);
            lotteryMetrics.waitlistClosed(closed);
        }
    }
}
