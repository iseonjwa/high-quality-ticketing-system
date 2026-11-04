package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.reservation.lottery.domain.DrawEntrant;
import com.iseonjwa.reservation.lottery.domain.LotteryEntryRepository;
import com.iseonjwa.reservation.lottery.domain.LotteryEntryRepository.UnassignedWinner;
import com.iseonjwa.reservation.lottery.domain.LotterySeat;
import com.iseonjwa.reservation.lottery.domain.SeatAllocator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 당첨자 배정(D10, details.md 4절 배정 워커). 당첨자 1명 = 1트랜잭션으로 좌석을 고르고 예매를 만들어 응모에 연결한다.
 * 첫 배정, 승계 배정(ISE-93), {@code NOT_SENT} 재시도(D12)가 모두 "WON이고 예매가 없는 응모"라 같은 경로를 탄다.
 */
@Service
@RequiredArgsConstructor
public class LotteryAssignmentService {
    public static final String NO_SEAT = "NO_SEAT";
    // 같은 응모에서 예상하지 못한 실패가 반복돼 배정을 포기한 경우(ISE-154)
    public static final String ASSIGN_ERROR = "ASSIGN_ERROR";

    private final LotteryEntryRepository lotteryEntryRepository;
    private final LotterySeatPort lotterySeatPort;
    private final SystemHoldPort systemHoldPort;
    private final BillingPaymentRequestPublisher billingPaymentRequestPublisher;

    /**
     * 당첨자 한 명을 배정한다. 이 트랜잭션이 롤백되면 응모는 그대로 WON·예매 없음으로 남아 다음 주기가 다시 집는다.
     *
     * @return 배정을 시도한 응모 ID. 배정할 당첨자가 없으면 비어 있다
     * @throws AssignmentRejected 매수 한도 초과·남은 자리 없음처럼 다시 해도 안 되는 거절. 부른 쪽이 FAILED로 기록한다
     */
    // Note: 예매 생성(시스템 선점), 응모 연결, 자동결제 요청(outbox)을 한 트랜잭션에 둔다. 나눠 커밋하면 예매만 남고
    // 연결이나 결제 요청이 빠진 채 워커가 죽을 수 있다. 시스템 선점은 MANDATORY라 이 트랜잭션 밖에서는 불리지 않는다(ISE-88)
    @Transactional
    public Optional<Long> assignNext() {
        return assignNext(Set.of());
    }

    /**
     * {@code skipEntryIds}를 빼고 다음 당첨자를 배정한다. 스케줄러가 이번 주기에 이미 실패한 응모를 다시 집지 않게 넘긴다.
     *
     * @throws AssignmentError 예상하지 못한 실패. 그 응모 ID를 담아 스케줄러가 건너뛰고 실패 횟수를 센다(ISE-154)
     */
    @Transactional
    public Optional<Long> assignNext(Collection<Long> skipEntryIds) {
        var next = lotteryEntryRepository.lockNextUnassignedWinner(skipEntryIds);
        if (next.isEmpty()) {
            return Optional.empty();
        }
        var winner = next.get();
        try {
            var seatIds = pickSeats(winner);
            var hold = systemHoldPort.hold(winner.userId(), winner.scheduleId(), seatIds);
            if (!lotteryEntryRepository.linkReservation(winner.entryId(), hold.reservationId())) {
                throw new IllegalStateException("잠근 당첨 응모에 예매를 연결하지 못했다: " + winner.entryId());
            }
            billingPaymentRequestPublisher.publish(hold.reservationId(), winner.userId(), hold.totalAmount(),
                    winner.entryId());
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
     * 배정 거절을 새 트랜잭션에서 기록한다. 거절한 배정 트랜잭션은 이미 롤백돼 매수·좌석 변경이 남지 않는다.
     *
     * @return FAILED로 닫았으면 true. {@code NO_SEAT}는 승계 마감 전이면 닫지 않고 false다(ISE-153)
     */
    // Note: 남은 추첨석이 없는 이유는 대개 앞 당첨자의 결제 실패 좌석이 아직 반환되지 않아서다(결제 결과 소비자가 반환).
    // 바로 FAILED로 닫으면 반환 몇 초 차이로 정당한 예비자가 탈락한다. 승계 마감까지는 WON으로 두고 다시 시도한다
    @Transactional
    public boolean markRejected(Long entryId, String reason) {
        if (NO_SEAT.equals(reason)) {
            return lotteryEntryRepository.markNoSeatFailedAfterSuccessionDeadline(entryId);
        }
        return lotteryEntryRepository.markAssignmentFailed(entryId, reason);
    }

    // 남은 추첨석에서 고른다. 2매면 옆자리 쌍을 먼저 찾는다(SeatAllocator, D9). 모자라면 다시 해도 안 되는 거절이다
    private List<Long> pickSeats(UnassignedWinner winner) {
        List<LotterySeat> free = lotterySeatPort.findAvailableLotterySeats(winner.scheduleId());
        var assignments = SeatAllocator.succeed(List.of(new DrawEntrant(winner.entryId(), winner.quantity())), free);
        if (assignments.isEmpty()) {
            throw new AssignmentRejected(winner.entryId(), NO_SEAT);
        }
        return assignments.getFirst().seats().stream().map(LotterySeat::seatId).toList();
    }

    /**
     * 배정 중 예상하지 못한 실패(데이터 오류 등). 이 예외로 배정 트랜잭션을 롤백하고, 스케줄러가 응모를 건너뛰며 횟수를 센다.
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
     * 다시 해도 같은 결과인 배정 거절. 이 예외로 배정 트랜잭션을 롤백하고, 부른 쪽이 {@link #markRejected}로 남긴다.
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
