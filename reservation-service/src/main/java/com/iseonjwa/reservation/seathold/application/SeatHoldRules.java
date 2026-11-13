package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatus;
import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldRequestRepository;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/**
 * 동기형·접수형 선점이 같이 쓰는 입력 검증과 멱등 판정(contracts.md 1·3절). 두 모드가 같은 요청을 같은 기준으로 받아야
 * 재측정(ISE-18)에서 차이가 접수 방식에서만 나온다.
 */
final class SeatHoldRules {
    private SeatHoldRules() {}

    // 정렬한 목록을 응답·seat_hold_requests.seat_ids·멱등 본문 비교의 기준으로 같이 쓴다(contracts.md 1절)
    static List<Long> sortedDistinctSeatIds(List<Long> requested) {
        if (requested.isEmpty() || new HashSet<>(requested).size() != requested.size()) {
            throw new BusinessException(SeatHoldErrorCode.SEAT_COUNT_MISMATCH);
        }
        return requested.stream().sorted().toList();
    }

    static void requireWithinPerUserLimit(Schedule schedule, List<Long> seatIds) {
        if (seatIds.size() > schedule.getMaxPerUser()) {
            throw new BusinessException(SeatHoldErrorCode.SEAT_COUNT_MISMATCH);
        }
    }

    // 판매 기간은 오픈 시각부터 공연 시작 전까지다. 시각은 클라이언트 값이 아니라 서버 시계로 판단한다(E4)
    static void requireOnSale(Schedule schedule, Instant now) {
        if (now.isBefore(schedule.getOpenAt()) || !now.isBefore(schedule.getStartsAt())) {
            throw new BusinessException(SeatHoldErrorCode.SALE_NOT_OPEN);
        }
    }

    // 입장 토큰은 선점이 성공한 순간 소모한다(ADR 0053). 거절되지 않은 요청(처리 중·성공)이 이미 쓴 토큰이면 거절한다.
    // 이 조회는 빠른 거절용이고, 동시에 온 요청은 저장할 때 부분 유니크 인덱스가 막는다
    static void requireUnusedEntryToken(SeatHoldRequestRepository requests, HoldSeatsCommand command) {
        if (command.entryTokenJti() != null && requests.existsActiveByEntryTokenJti(command.entryTokenJti())) {
            throw new BusinessException(SeatHoldErrorCode.ENTRY_TOKEN_USED);
        }
    }

    // 같은 멱등키는 같은 사용자·회차·좌석일 때만 재요청으로 인정한다(contracts.md 3-1)
    static void requireSameRequest(SeatHoldRequest previous, HoldSeatsCommand command, List<Long> seatIds) {
        if (!previous.getUserId().equals(command.userId())
                || !previous.getScheduleId().equals(command.scheduleId())
                || !previous.getSeatIds().equals(seatIds)) {
            throw new BusinessException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
    }

    // Note: 만료 스케줄러가 돌기 전이면 기한이 지나도 HELD로 남아 있어서 상태만 보지 않고 시각도 본다
    static boolean isPayable(Reservation reservation, Instant now) {
        return reservation.getStatus() == ReservationStatus.HELD
                && now.isBefore(reservation.getHoldExpiresAt().minus(PaymentTokenIssuer.EXPIRY_MARGIN));
    }
}
