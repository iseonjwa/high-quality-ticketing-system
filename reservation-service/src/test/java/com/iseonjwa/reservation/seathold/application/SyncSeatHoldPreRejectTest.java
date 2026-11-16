package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationRepository;
import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldInventoryRepository;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldRequestRepository;
import com.iseonjwa.reservation.seathold.observability.SeatHoldMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 이미 잡힌 좌석을 노린 선점을 트랜잭션 전에 거절하는지 본다(ISE-281). 쓰기 트랜잭션이 열렸는지는
// 트랜잭션 관리자가 받은 정의의 readOnly로 가린다. 실제 HTTP·캐시 연결은 SeatHoldPreRejectApiTest가 본다
class SyncSeatHoldPreRejectTest {
    private static final long USER = 7L;
    private static final long SCHEDULE = 70L;
    private static final List<Long> SEATS = List.of(701L, 702L);

    private final SeatHoldInventoryRepository inventory = mock(SeatHoldInventoryRepository.class);
    private final SeatHoldRequestRepository requests = mock(SeatHoldRequestRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final HeldSeatPreCheck preCheck = mock(HeldSeatPreCheck.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private SyncSeatHoldService service;

    @BeforeEach
    void setUp() {
        service = new SyncSeatHoldService(inventory, mock(ReservationRepository.class), mock(SeatHolding.class),
                requests, mock(HeldReservationResults.class), mock(ConsumedEntryTokens.class), Clock.systemUTC(),
                transactions, preCheck, new SeatHoldMetrics(registry));
    }

    @Test
    void 캐시가_이미_잡힘이라_하면_쓰기_트랜잭션과_좌석_조회_없이_409다() {
        var command = command();
        when(preCheck.anyNotHoldable(SCHEDULE, SEATS)).thenReturn(true);
        when(requests.findById(command.requestId())).thenReturn(Optional.empty());

        var rejected = assertThrows(BusinessException.class, () -> service.hold(command));

        assertEquals(SeatHoldErrorCode.SEAT_ALREADY_HELD, rejected.getErrorCode());
        verifyNoInteractions(inventory);
        verify(transactions, never()).getTransaction(argThat(definition -> !definition.isReadOnly()));
        verify(requests, never()).save(any());
        assertEquals(1.0, preRejected());
    }

    @Test
    void 캐시가_모르거나_가능이라_하면_지금처럼_쓰기_트랜잭션으로_간다() {
        var command = command();
        when(preCheck.anyNotHoldable(SCHEDULE, SEATS)).thenReturn(false);
        when(requests.findById(command.requestId())).thenReturn(Optional.empty());
        when(inventory.findSchedule(SCHEDULE)).thenReturn(Optional.empty());

        // 회차가 없어 404로 끝나게 해 트랜잭션 안으로 들어갔는지만 본다. 409(사전 거절)가 아니어야 한다
        var rejected = assertThrows(BusinessException.class, () -> service.hold(command));

        assertEquals(CommonErrorCode.NOT_FOUND, rejected.getErrorCode());
        verify(transactions).getTransaction(argThat(definition -> !definition.isReadOnly()));
        verify(inventory).findSchedule(SCHEDULE);
        assertEquals(0.0, preRejected());
    }

    @Test
    void 캐시가_이미_잡힘이라_해도_같은_멱등키의_앞선_요청이_있으면_그_결과로_답한다() {
        var command = command();
        when(preCheck.anyNotHoldable(SCHEDULE, SEATS)).thenReturn(true);
        // 접수형으로 시작해 처리 중인 앞선 요청. 재응답은 202다(ADR 0058). 예매를 만들지 않고도 재응답 경로를 탔는지 보인다
        when(requests.findById(command.requestId()))
                .thenReturn(Optional.of(SeatHoldRequest.request(command.requestId(), USER, SCHEDULE, SEATS)));

        var outcome = service.hold(command);

        assertInstanceOf(SyncHoldOutcome.Accepted.class, outcome);
        verifyNoInteractions(inventory);
        verify(transactions, never()).getTransaction(argThat(definition -> !definition.isReadOnly()));
        assertEquals(0.0, preRejected());
    }

    @Test
    void 사전_거절은_정렬한_좌석으로_묻는다() {
        var command = new HoldSeatsCommand(USER, UUID.randomUUID(), SCHEDULE, List.of(702L, 701L), null);
        when(preCheck.anyNotHoldable(any(), any())).thenReturn(false);
        when(requests.findById(command.requestId())).thenReturn(Optional.empty());
        when(inventory.findSchedule(SCHEDULE)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.hold(command));

        verify(preCheck).anyNotHoldable(SCHEDULE, SEATS);
    }

    private HoldSeatsCommand command() {
        return new HoldSeatsCommand(USER, UUID.randomUUID(), SCHEDULE, SEATS, null);
    }

    private double preRejected() {
        return registry.get("seathold.pre_rejected").counter().count();
    }
}
