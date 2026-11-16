package com.iseonjwa.reservation.lottery.presentation.scheduling;

import com.iseonjwa.reservation.lottery.application.LotteryAssignmentService;
import com.iseonjwa.reservation.lottery.application.LotteryAssignmentService.AssignmentError;
import com.iseonjwa.reservation.lottery.application.LotteryAssignmentService.AssignmentRejected;
import com.iseonjwa.reservation.lottery.observability.LotteryMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 한 주기에 성공 배정 {@code lottery.scheduler.assign-per-cycle}건(기본 10)까지, 시도는 최대 {@link #MAX_ATTEMPTS_PER_CYCLE}번
 * 각자 트랜잭션으로 배정해 실패한 한 명만 롤백하는 배정 워커(1초, details.md 4절).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "lottery.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class LotteryAssignmentScheduler {
    // 거절·오류가 이어져도 한 주기가 너무 길어지지 않게 끊고, 남은 당첨자는 다음 주기에 이어서 한다
    static final int MAX_ATTEMPTS_PER_CYCLE = 100;
    static final int MAX_ASSIGN_ERRORS = 3;

    private final LotteryAssignmentService assignmentService;
    private final LotteryMetrics lotteryMetrics;
    private final int maxAssignmentsPerCycle;
    // 인스턴스마다 따로 세므로 여러 인스턴스면 포기까지 조금 더 걸리지만 결과는 같다
    private final Map<Long, Integer> errorCounts = new ConcurrentHashMap<>();

    // Note: 배정 속도 상한이다(ISE-94 시나리오 3). 배정이 뒤 단계 자동결제(초당 약 9.5건)보다 빨라도 결제는 앞당겨지지 않고,
    // 같은 커넥션 풀(5)·DB를 쓰는 다른 회차 선점만 느려진다. 기다리지 않고 주기 사이 쉼(assign-interval)으로 속도를 낮춰
    // 스케줄러 스레드를 붙잡지 않는다. 바꿀 때는 docs/design/lottery/numbers-and-measurement.md 3-7절을 함께 고친다
    public LotteryAssignmentScheduler(LotteryAssignmentService assignmentService, LotteryMetrics lotteryMetrics,
            @Value("${lottery.scheduler.assign-per-cycle:10}") int maxAssignmentsPerCycle) {
        if (maxAssignmentsPerCycle < 1) {
            throw new IllegalArgumentException("lottery.scheduler.assign-per-cycle은 1 이상이어야 한다: " + maxAssignmentsPerCycle);
        }
        this.assignmentService = assignmentService;
        this.lotteryMetrics = lotteryMetrics;
        this.maxAssignmentsPerCycle = maxAssignmentsPerCycle;
    }

    @Scheduled(fixedDelayString = "${lottery.scheduler.assign-interval:1s}")
    public void assignWinners() {
        // Note: 대상 조회가 순위 순 LIMIT 1이라 이번 주기에 실패·보류한 응모를 건너뛰어야 뒤 당첨자가 막히지 않는다(ISE-153·154).
        Set<Long> skipped = new HashSet<>();
        // Note: 상한은 성공 배정만 센다. 시도 횟수로 세면 앞 순위의 승계 마감 전 NO_SEAT 보류가 상한보다 많을 때
        // 매 주기 그 응모만 다시 시도하다 끝나, 다른 추첨의 당첨자가 승계 마감(30분)까지 배정되지 못한다
        int assignedCount = 0;

        for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_CYCLE && assignedCount < maxAssignmentsPerCycle; attempt++) {
            try {
                if (assignmentService.assignNext(skipped).isEmpty()) {
                    return;
                }
                assignedCount++;
                lotteryMetrics.assigned();
            } catch (AssignmentRejected rejected) {
                if (assignmentService.markRejected(rejected.entryId(), rejected.reason())) {
                    log.info("추첨 당첨 배정 거절: entryId={}, reason={}", rejected.entryId(), rejected.reason());
                    lotteryMetrics.assignmentRejected(rejected.reason());
                } else {
                    skipped.add(rejected.entryId());
                }
            } catch (AssignmentError error) {
                skipped.add(error.entryId());
                recordError(error);
            } catch (RuntimeException exception) {
                // AssignmentError로 감싸지 못한 실패(대상 조회·커밋 등)는 응모를 특정할 수 없으니 이번 주기를 멈춘다
                log.error("추첨 당첨 배정 주기 중단, 다음 주기에 다시 시도", exception);
                lotteryMetrics.jobFailed(LotteryMetrics.Job.ASSIGNMENT);
                return;
            }
        }
    }

    // 같은 응모의 실패가 주기를 넘어 상한에 이르면 배정을 포기하고 FAILED로 닫아 승계 워커가 자리를 넘기게 한다
    private void recordError(AssignmentError error) {
        int failures = errorCounts.merge(error.entryId(), 1, Integer::sum);
        log.error("추첨 당첨 배정 실패: entryId={}, 연속 {}회", error.entryId(), failures, error);
        lotteryMetrics.assignmentError();
        if (failures >= MAX_ASSIGN_ERRORS) {
            errorCounts.remove(error.entryId());
            if (assignmentService.markRejected(error.entryId(), LotteryAssignmentService.ASSIGN_ERROR)) {
                lotteryMetrics.assignmentRejected(LotteryAssignmentService.ASSIGN_ERROR);
            }
        }
    }
}
