package com.iseonjwa.reservation.lottery.infrastructure.scheduler;

import com.iseonjwa.reservation.lottery.application.LotteryAssignmentService;
import com.iseonjwa.reservation.lottery.application.LotteryAssignmentService.AssignmentError;
import com.iseonjwa.reservation.lottery.application.LotteryAssignmentService.AssignmentRejected;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 배정 스케줄러의 건너뛰기와 실패 횟수(ISE-153·154). DB 없이 서비스를 대신해 순위 순 대상 조회를 흉내 낸다.
class LotteryAssignmentSchedulerTest {
    private LotteryAssignmentService service;
    private LotteryAssignmentScheduler scheduler;
    private List<Long> winners;
    private List<Long> assigned;

    @BeforeEach
    void setUp() {
        service = mock(LotteryAssignmentService.class);
        scheduler = new LotteryAssignmentScheduler(service);
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
    }

    // 순위 순으로 건너뛰지 않은 첫 당첨자를 고른다. failing 응모는 매번 같은 예외를 던지고, 나머지는 배정된다
    @SuppressWarnings("unchecked")
    private void stub(Long failing, RuntimeException failure) {
        when(service.assignNext(any(Collection.class))).thenAnswer(invocation -> {
            Collection<Long> skip = invocation.getArgument(0);
            var next = winners.stream().filter(id -> !skip.contains(id) && !assigned.contains(id)).findFirst();
            if (next.isEmpty()) return Optional.empty();
            if (next.get().equals(failing)) throw failure;
            assigned.add(next.get());
            return Optional.of(next.get());
        });
        when(service.markRejected(any(), any())).thenReturn(true);
    }
}
