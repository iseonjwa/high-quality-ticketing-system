package com.iseonjwa.reservation.lottery.presentation.scheduling;

import com.iseonjwa.reservation.lottery.application.LotteryAssignmentService;
import com.iseonjwa.reservation.lottery.application.LotteryAssignmentService.AssignmentError;
import com.iseonjwa.reservation.lottery.application.LotteryAssignmentService.AssignmentRejected;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 한 주기에 최대 {@link #MAX_ASSIGNMENTS_PER_CYCLE}명을 각자 트랜잭션으로 배정해 실패한 한 명만 롤백하는 배정 워커(1초, details.md 4절).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "lottery.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class LotteryAssignmentScheduler {
    // 한 주기가 너무 길어지지 않게 끊고, 남은 당첨자는 다음 주기에 이어서 한다
    static final int MAX_ASSIGNMENTS_PER_CYCLE = 100;
    static final int MAX_ASSIGN_ERRORS = 3;

    private final LotteryAssignmentService assignmentService;
    // 인스턴스마다 따로 세므로 여러 인스턴스면 포기까지 조금 더 걸리지만 결과는 같다
    private final Map<Long, Integer> errorCounts = new ConcurrentHashMap<>();

    public LotteryAssignmentScheduler(LotteryAssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @Scheduled(fixedDelayString = "${lottery.scheduler.assign-interval:1s}")
    public void assignWinners() {
        // Note: 대상 조회가 순위 순 LIMIT 1이라 이번 주기에 실패·보류한 응모를 건너뛰어야 뒤 당첨자가 막히지 않는다(ISE-153·154).
        Set<Long> skipped = new HashSet<>();

        for (int i = 0; i < MAX_ASSIGNMENTS_PER_CYCLE; i++) {
            try {
                if (assignmentService.assignNext(skipped).isEmpty()) {
                    return;
                }
            } catch (AssignmentRejected rejected) {
                if (assignmentService.markRejected(rejected.entryId(), rejected.reason())) {
                    log.info("추첨 당첨 배정 거절: entryId={}, reason={}", rejected.entryId(), rejected.reason());
                } else {
                    skipped.add(rejected.entryId());
                }
            } catch (AssignmentError error) {
                skipped.add(error.entryId());
                recordError(error);
            } catch (RuntimeException exception) {
                // AssignmentError로 감싸지 못한 실패(대상 조회·커밋 등)는 응모를 특정할 수 없으니 이번 주기를 멈춘다
                log.error("추첨 당첨 배정 주기 중단, 다음 주기에 다시 시도", exception);
                return;
            }
        }
    }

    // 같은 응모의 실패가 주기를 넘어 상한에 이르면 배정을 포기하고 FAILED로 닫아 승계 워커가 자리를 넘기게 한다
    private void recordError(AssignmentError error) {
        int failures = errorCounts.merge(error.entryId(), 1, Integer::sum);
        log.error("추첨 당첨 배정 실패: entryId={}, 연속 {}회", error.entryId(), failures, error);
        if (failures >= MAX_ASSIGN_ERRORS) {
            errorCounts.remove(error.entryId());
            assignmentService.markRejected(error.entryId(), LotteryAssignmentService.ASSIGN_ERROR);
        }
    }
}
