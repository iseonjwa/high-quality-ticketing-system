package com.iseonjwa.reservation.queue.application;

import com.iseonjwa.reservation.queue.domain.EntryTokenJti;
import com.iseonjwa.reservation.queue.domain.QueueRepository;
import com.iseonjwa.reservation.queue.domain.QueueStorageUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 선점에 쓴 입장 토큰을 issued에서 지울 때, 지금 기록이 그 토큰일 때만 지우는지 확인한다 (ISE-207, ADR 0053)
class QueueServiceDiscardTest {

    static final long SCHEDULE_ID = 31L;
    static final long USER_ID = 42L;
    static final long ISSUED_AT = 1_793_176_230_123L;
    static final String JTI = EntryTokenJti.of(SCHEDULE_ID, USER_ID, ISSUED_AT);

    final QueueRepository queueRepository = mock(QueueRepository.class);
    // 정리에는 저장소만 쓴다
    final QueueService queueService = new QueueService(null, null, queueRepository, null, null);

    @Test
    void 지금_기록이_넘겨받은_토큰이면_그_발급_시각으로_지운다() {
        when(queueRepository.issuedAt(SCHEDULE_ID, USER_ID)).thenReturn(Optional.of(ISSUED_AT));

        queueService.discardEntryToken(SCHEDULE_ID, USER_ID, JTI);

        verify(queueRepository).discardToken(SCHEDULE_ID, USER_ID, ISSUED_AT);
    }

    @Test
    void 그_사이_새_토큰을_받았으면_지우지_않는다() {
        // 옛 토큰(ISSUED_AT)으로 늦게 온 정리. 지금 기록은 새로 받은 토큰이다
        when(queueRepository.issuedAt(SCHEDULE_ID, USER_ID)).thenReturn(Optional.of(ISSUED_AT + 60_000));

        queueService.discardEntryToken(SCHEDULE_ID, USER_ID, JTI);

        verify(queueRepository, never()).discardToken(anyLong(), anyLong(), anyLong());
    }

    @Test
    void 발급_기록이_없으면_아무것도_하지_않는다() {
        when(queueRepository.issuedAt(SCHEDULE_ID, USER_ID)).thenReturn(Optional.empty());

        queueService.discardEntryToken(SCHEDULE_ID, USER_ID, JTI);

        verify(queueRepository, never()).discardToken(anyLong(), anyLong(), anyLong());
    }

    @Test
    void jti가_없으면_지우지_않는다() {
        when(queueRepository.issuedAt(SCHEDULE_ID, USER_ID)).thenReturn(Optional.of(ISSUED_AT));

        queueService.discardEntryToken(SCHEDULE_ID, USER_ID, null);

        verify(queueRepository, never()).discardToken(anyLong(), anyLong(), anyLong());
    }

    @Test
    void Redis를_못_쓰면_대기열_저장소_예외가_그대로_나간다() {
        // 선점 쪽이 잡아 로그만 남기기로 했다(ISE-207 댓글)
        when(queueRepository.issuedAt(SCHEDULE_ID, USER_ID))
                .thenThrow(new QueueStorageUnavailableException(new RuntimeException("Redis command timed out")));

        assertThrows(QueueStorageUnavailableException.class,
                () -> queueService.discardEntryToken(SCHEDULE_ID, USER_ID, JTI));
    }
}
