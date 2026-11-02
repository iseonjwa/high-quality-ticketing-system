package com.iseonjwa.reservation.lottery.infrastructure.scheduler;

import com.iseonjwa.reservation.lottery.application.LotteryAssignmentService;
import com.iseonjwa.reservation.lottery.application.LotteryAssignmentService.AssignmentRejected;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 배정 워커(1초, details.md 4절). 한 주기에 최대 {@link #BATCH}명을 한 명씩 각자 트랜잭션으로 배정한다.
 * 한 명이 실패해도 그 트랜잭션만 롤백되고, 그 응모는 다음 주기가 다시 집는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "lottery.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class LotteryAssignmentScheduler {
    // 한 주기가 너무 길어지지 않게 끊는다. 남은 당첨자는 다음 주기에 이어서 한다
    static final int BATCH = 100;

    private final LotteryAssignmentService assignmentService;

    public LotteryAssignmentScheduler(LotteryAssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @Scheduled(fixedDelayString = "${lottery.scheduler.assign-interval:1s}")
    public void assignWinners() {
        for (int i = 0; i < BATCH; i++) {
            try {
                if (assignmentService.assignNext().isEmpty()) {
                    return;
                }
            } catch (AssignmentRejected rejected) {
                assignmentService.markRejected(rejected.entryId(), rejected.reason());
                log.info("추첨 당첨 배정 거절: entryId={}, reason={}", rejected.entryId(), rejected.reason());
            } catch (RuntimeException exception) {
                // 예상하지 못한 실패는 이번 주기를 멈춘다. 같은 응모가 다시 앞에 오므로 계속 돌면 같은 실패만 반복한다
                log.error("추첨 당첨 배정 실패, 다음 주기에 다시 시도", exception);
                return;
            }
        }
    }
}
