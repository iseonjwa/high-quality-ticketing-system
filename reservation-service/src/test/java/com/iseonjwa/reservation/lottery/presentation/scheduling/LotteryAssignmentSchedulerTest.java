package com.iseonjwa.reservation.lottery.presentation.scheduling;

import com.iseonjwa.reservation.lottery.application.LotteryAssignmentService;
import com.iseonjwa.reservation.lottery.application.LotteryAssignmentService.AssignmentError;
import com.iseonjwa.reservation.lottery.application.LotteryAssignmentService.AssignmentRejected;
import com.iseonjwa.reservation.lottery.observability.LotteryMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 배정 스케줄러의 건너뛰기와 실패 횟수(ISE-153·154), 주기당 배정 상한(ISE-94). DB 없이 서비스를 대신해 순위 순 대상 조회를 흉내 낸다.
class LotteryAssignmentSchedulerTest {
    private static final int DEFAULT_ASSIGN_PER_CYCLE = 10;

    private LotteryAssignmentService service;
    private LotteryAssignmentScheduler scheduler;
    private SimpleMeterRegistry registry;
    private List<Long> winners;
    private List<Long> assigned;

    @BeforeEach
    void setUp() {
        service = mock(LotteryAssignmentService.class);
        registry = new SimpleMeterRegistry();
        scheduler = schedulerWithCap(DEFAULT_ASSIGN_PER_CYCLE);
        winners = new ArrayList<>(List.of(1L, 2L, 3L));
        assigned = new ArrayList<>();
    }

    // 완료 기준(ISE-154): 맨 앞 응모가 실패해도 같은 주기에 뒤 당첨자가 배정된다
    @Test
    void 실패한_응모를_건너뛰고_같은_주기에_다음_당첨자를_배정한다() {
        stub(1L, new AssignmentError(1L, new IllegalStateException("데이터 오류")));

        scheduler.assignWinners();

        assertEquals(List.of(2L, 3L), assigned);
        verify(service, never()).markRejected(eq(1L), any());
    }

    // 완료 기준(ISE-154): 주기를 넘어 연속 3번 실패하면 ASSIGN_ERROR로 닫는다
    @Test
    void 같은_응모가_세_번_연속_실패하면_ASSIGN_ERROR로_닫는다() {
        stub(1L, new AssignmentError(1L, new IllegalStateException("데이터 오류")));

        scheduler.assignWinners();
        scheduler.assignWinners();
        verify(service, never()).markRejected(eq(1L), any());
        scheduler.assignWinners();

        verify(service, times(1)).markRejected(1L, LotteryAssignmentService.ASSIGN_ERROR);
    }

    // ISE-153: 닫지 않은 거절(NO_SEAT, 마감 전)은 이번 주기에 건너뛰고 뒤 당첨자는 계속 배정한다
    @Test
    void 마감_전_NO_SEAT는_건너뛰고_뒤_당첨자를_배정한다() {
        stub(1L, new AssignmentRejected(1L, LotteryAssignmentService.NO_SEAT));
        when(service.markRejected(1L, LotteryAssignmentService.NO_SEAT)).thenReturn(false);

        scheduler.assignWinners();

        assertEquals(List.of(2L, 3L), assigned);
        assertEquals(0, assignmentResult("rejected"));
    }

    // ISE-94: 배정·오류·거절을 결과별로 세고, 포기한 응모는 실패 사유로도 센다
    @Test
    void 배정_결과를_종류별로_센다() {
        stub(1L, new AssignmentError(1L, new IllegalStateException("데이터 오류")));

        scheduler.assignWinners();
        scheduler.assignWinners();
        scheduler.assignWinners();

        assertEquals(2, assignmentResult("assigned"));
        assertEquals(3, assignmentResult("error"));
        assertEquals(1, assignmentResult("rejected"));
        assertEquals(1, registry.get("lottery.entry.failures").tag("reason", "ASSIGN_ERROR").counter().count());
    }

    // ISE-94: 응모를 특정할 수 없어 주기를 멈춘 실패는 작업 실패로 센다
    @Test
    void 대상_조회가_실패하면_작업_실패로_센다() {
        when(service.assignNext(any(Collection.class))).thenThrow(new IllegalStateException("커밋 실패"));

        scheduler.assignWinners();

        assertEquals(1, registry.get("lottery.job.failures").tag("task", "assignment").counter().count());
    }

    // ISE-94 시나리오 3: 당첨자가 상한보다 많으면 이번 주기는 상한만큼만 배정하고 남은 당첨자는 다음 주기가 잇는다
    @Test
    void 당첨자가_상한보다_많으면_한_주기에_상한만큼만_배정하고_다음_주기가_이어받는다() {
        winners = winnerIds(5);
        scheduler = schedulerWithCap(2);
        stub(Map.of());

        scheduler.assignWinners();
        assertEquals(List.of(1L, 2L), assigned);

        scheduler.assignWinners();
        assertEquals(List.of(1L, 2L, 3L, 4L), assigned);
    }

    // ISE-94: 상한은 성공 배정만 센다. 앞 순위 보류(마감 전 NO_SEAT)가 상한보다 많아도 뒤 당첨자가 같은 주기에 배정된다
    @Test
    void 보류된_NO_SEAT는_상한에_세지_않아_뒤_당첨자가_배정된다() {
        winners = winnerIds(5);
        scheduler = schedulerWithCap(2);
        stub(Map.of(
                1L, new AssignmentRejected(1L, LotteryAssignmentService.NO_SEAT),
                2L, new AssignmentRejected(2L, LotteryAssignmentService.NO_SEAT),
                3L, new AssignmentRejected(3L, LotteryAssignmentService.NO_SEAT)));
        when(service.markRejected(any(), eq(LotteryAssignmentService.NO_SEAT))).thenReturn(false);

        scheduler.assignWinners();

        assertEquals(List.of(4L, 5L), assigned);
    }

    // ISE-94: 거절·오류가 끝없이 이어져도 한 주기의 시도는 100번에서 끊는다
    @Test
    void 성공이_없어도_한_주기의_시도는_상한에서_끊는다() {
        when(service.assignNext(any(Collection.class))).thenThrow(new AssignmentError(1L, new IllegalStateException("데이터 오류")));

        scheduler.assignWinners();

        verify(service, times(LotteryAssignmentScheduler.MAX_ATTEMPTS_PER_CYCLE)).assignNext(any(Collection.class));
    }

    @Test
    void 주기당_상한이_1보다_작으면_만들지_않는다() {
        assertThrows(IllegalArgumentException.class, () -> schedulerWithCap(0));
    }

    // 설정 키와 기본값(10)이 실제 빈 생성에 쓰이는지 본다. 측정 덮어쓰기(LOTTERY_SCHEDULER_ASSIGNPERCYCLE)가 이 키로 들어온다
    @Test
    void 설정이_없으면_주기당_10건_있으면_그_값만큼_배정한다() {
        var runner = new ApplicationContextRunner()
                .withBean(LotteryAssignmentService.class, () -> service)
                .withBean(LotteryMetrics.class, () -> new LotteryMetrics(registry, Clock.systemUTC()))
                .withBean(LotteryAssignmentScheduler.class);
        winners = winnerIds(30);
        stub(Map.of());

        runner.run(context -> context.getBean(LotteryAssignmentScheduler.class).assignWinners());
        assertEquals(10, assigned.size());

        assigned.clear();
        runner.withPropertyValues("lottery.scheduler.assign-per-cycle=3")
                .run(context -> context.getBean(LotteryAssignmentScheduler.class).assignWinners());
        assertEquals(3, assigned.size());
    }

    private LotteryAssignmentScheduler schedulerWithCap(int assignPerCycle) {
        return new LotteryAssignmentScheduler(service, new LotteryMetrics(registry, Clock.systemUTC()), assignPerCycle);
    }

    private static List<Long> winnerIds(int count) {
        return new ArrayList<>(LongStream.rangeClosed(1, count).boxed().toList());
    }

    private double assignmentResult(String result) {
        return registry.get("lottery.assignment.results").tag("result", result).counter().count();
    }

    private void stub(Long failing, RuntimeException failure) {
        stub(Map.of(failing, failure));
    }

    // 순위 순으로 건너뛰지 않은 첫 당첨자를 고른다. failures에 있는 응모는 매번 같은 예외를 던지고, 나머지는 배정된다
    @SuppressWarnings("unchecked")
    private void stub(Map<Long, RuntimeException> failures) {
        when(service.assignNext(any(Collection.class))).thenAnswer(invocation -> {
            Collection<Long> skip = invocation.getArgument(0);
            var next = winners.stream().filter(id -> !skip.contains(id) && !assigned.contains(id)).findFirst();
            if (next.isEmpty()) return Optional.empty();
            RuntimeException failure = failures.get(next.get());
            if (failure != null) throw failure;
            assigned.add(next.get());
            return Optional.of(next.get());
        });
        when(service.markRejected(any(), any())).thenReturn(true);
    }
}
