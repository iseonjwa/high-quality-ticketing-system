package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.reservation.lottery.domain.DrawEntrant;
import com.iseonjwa.reservation.lottery.domain.LotteryEntryRepository;
import com.iseonjwa.reservation.lottery.domain.LotteryEntryRepository.UnassignedWinner;
import com.iseonjwa.reservation.lottery.domain.LotterySeat;
import com.iseonjwa.reservation.lottery.domain.SeatAllocator;
import com.iseonjwa.reservation.lottery.domain.SeatAllocator.SeatAssignment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 당첨자 1명을 1트랜잭션으로 배정하며, 첫 배정·승계 배정·{@code NOT_SENT} 재시도가 모두 같은 경로를 탄다(D10, D12, details.md 4절).
 */
@Service
@RequiredArgsConstructor
public class LotteryAssignmentService {
    public static final String NO_SEAT = "NO_SEAT";
    // 같은 응모에서 예상하지 못한 실패가 반복돼 배정을 포기했다(ISE-154)
    public static final String ASSIGN_ERROR = "ASSIGN_ERROR";

    private final LotteryEntryRepository lotteryEntryRepository;
    private final LotterySeatPort lotterySeatPort;
    private final SystemHoldPort systemHoldPort;
    private final BillingPaymentRequestPublisher billingPaymentRequestPublisher;

    /**
     * 건너뛸 응모 없이 다음 당첨자를 배정하며, 운영 코드는 {@link #assignNext(Collection)}를 쓴다.
     */
    @Transactional
    public Optional<Long> assignNext() {
        return assignNext(Set.of());
    }

    /**
     * 다음 당첨자 한 명을 배정하며, 롤백되면 응모는 WON·예매 없음으로 남아 다음 주기가 다시 집는다.
     *
     * @param skipEntryIds 스케줄러가 이번 주기에 이미 실패·보류해 다시 집지 않을 응모
     * @return 배정을 시도한 응모 ID. 배정할 당첨자가 없으면 비어 있다
     * @throws AssignmentRejected 업무 규칙으로 예매를 만들지 못한 거절. 부른 쪽이 {@link #markRejected}로 기록한다
     * @throws AssignmentError    예상하지 못한 실패. 스케줄러가 건너뛰고 실패 횟수를 센다(ISE-154)
     */
    // Note: 나눠 커밋하면 예매만 남고 연결이나 결제 요청이 빠진 채 워커가 죽을 수 있어 시스템 선점(MANDATORY)·응모 연결·outbox를 한 트랜잭션에 둔다(ISE-88).
    @Transactional
    public Optional<Long> assignNext(Collection<Long> skipEntryIds) {
        Optional<UnassignedWinner> next = lotteryEntryRepository.lockNextUnassignedWinner(skipEntryIds);
        if (next.isEmpty()) {
            return Optional.empty();
        }

        UnassignedWinner winner = next.get();
        try {
            assign(winner);
        } catch (AssignmentRejected rejected) {
            throw rejected;
        } catch (BusinessException exception) {
            throw new AssignmentRejected(winner.entryId(), exception.getErrorCode().code());
        } catch (RuntimeException exception) {
            throw new AssignmentError(winner.entryId(), exception);
        }

        return Optional.of(winner.entryId());
    }

    /**
     * 롤백된 배정 트랜잭션과 별도로 거절을 새 트랜잭션에 기록한다.
     *
     * @return FAILED로 닫았으면 true. {@code NO_SEAT}는 승계 마감 전이면 닫지 않고 false다(ISE-153)
     */
    // Note: 남은 추첨석이 없는 이유는 대개 앞 당첨자의 좌석 반환 지연이라, 정당한 예비자가 탈락하지 않게 승계 마감까지 WON으로 두고 다시 시도한다.
    @Transactional
    public boolean markRejected(Long entryId, String reason) {
        if (NO_SEAT.equals(reason)) {
            return lotteryEntryRepository.markNoSeatFailedAfterSuccessionDeadline(entryId);
        }
        return lotteryEntryRepository.markAssignmentFailed(entryId, reason);
    }

    private void assign(UnassignedWinner winner) {
        List<Long> seatIds = pickSeats(winner);
        SystemHoldPort.LotteryHold hold = systemHoldPort.hold(winner.userId(), winner.scheduleId(), seatIds);

        if (!lotteryEntryRepository.linkReservation(winner.entryId(), hold.reservationId())) {
            throw new IllegalStateException("잠근 당첨 응모에 예매를 연결하지 못했다: " + winner.entryId());
        }

        billingPaymentRequestPublisher.publish(
                hold.reservationId(),
                winner.userId(),
                hold.totalAmount(),
                winner.entryId());
    }

    // 남은 추첨석에서 고르며(2매는 옆자리 우선, D9), 모자라면 승계 마감까지 다시 시도할 NO_SEAT 거절이다
    private List<Long> pickSeats(UnassignedWinner winner) {
        List<LotterySeat> availableSeats = lotterySeatPort.findAvailableLotterySeats(winner.scheduleId());
        DrawEntrant entrant = new DrawEntrant(winner.entryId(), winner.quantity());

        List<SeatAssignment> assignments = SeatAllocator.succeed(List.of(entrant), availableSeats);
        if (assignments.isEmpty()) {
            throw new AssignmentRejected(winner.entryId(), NO_SEAT);
        }

        return assignments.getFirst().seats().stream()
                .map(LotterySeat::seatId)
                .toList();
    }

    /**
     * 배정 트랜잭션을 롤백하고 스케줄러가 응모를 건너뛰며 횟수를 세는, 배정 중 예상하지 못한 실패(데이터 오류 등).
     */
    public static final class AssignmentError extends RuntimeException {
        private final Long entryId;

        public AssignmentError(Long entryId, Throwable cause) {
            super("당첨 배정 실패: entryId=" + entryId, cause);
            this.entryId = entryId;
        }

        public Long entryId() {
            return entryId;
        }
    }

    /**
     * 배정 트랜잭션을 롤백하고 부른 쪽이 {@link #markRejected}로 남기는, 업무 규칙에 따른 배정 거절.
     */
    public static final class AssignmentRejected extends RuntimeException {
        private final Long entryId;
        private final String reason;

        public AssignmentRejected(Long entryId, String reason) {
            super("당첨 배정 거절: entryId=" + entryId + ", reason=" + reason, null, false, false);
            this.entryId = entryId;
            this.reason = reason;
        }

        public Long entryId() {
            return entryId;
        }

        public String reason() {
            return reason;
        }
    }
}
