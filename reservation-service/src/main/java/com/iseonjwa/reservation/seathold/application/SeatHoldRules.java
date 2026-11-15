package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;
import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldInventoryRepository;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldRequestRepository;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

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

    // Note: 잠그지 않고 읽은 상태라 그사이 바뀔 수 있다. 여기서 AVAILABLE이어도 좌석 조건부 UPDATE가 최종 판단이다.
    // 여기서 잡혀 보인 좌석은 커밋된 선점이라 UPDATE까지 가도 409다. 오픈 피크 요청 대부분이 이 경우라 먼저 거른다(ADR 0057, 0060)
    static boolean allHoldable(List<ScheduleSeat> seats) {
        return seats.stream().allMatch(ScheduleSeat::isHoldable);
    }

    // 사전 판정 재료를 한 문장으로 읽는다. 좌석이 하나도 없을 때(없는 회차·모두 다른 회차의 좌석, 드묾)만 멱등키·토큰을
    // 따로 읽는다. 판정 순서(멱등키 → 토큰 → 회차 → 매수 한도 → 판매 기간 → 좌석 수 → 먼저 거절)는 호출한 쪽이 지킨다
    static SeatHoldInventoryRepository.HoldPrecheck precheck(SeatHoldInventoryRepository inventory,
            SeatHoldRequestRepository requests, Long scheduleId, List<Long> seatIds, UUID requestId,
            String entryTokenJti) {
        var found = inventory.precheck(scheduleId, seatIds, requestId, entryTokenJti);
        if (!found.seats().isEmpty()) {
            return found;
        }
        var keyState = requests.findKeyState(requestId, entryTokenJti);
        return new SeatHoldInventoryRepository.HoldPrecheck(List.of(), keyState.requestExists(), keyState.tokenActive());
    }

    // 회차와 좌석을 한 번에 읽는다. 검사 순서(회차 없음 → 매수 한도 → 판매 기간 → 좌석 수)는 호출한 쪽이 지금처럼 지킨다
    static List<ScheduleSeat> loadSeatsWithSchedule(SeatHoldInventoryRepository inventory, Long scheduleId,
            List<Long> seatIds) {
        return inventory.findSeatsWithSchedule(scheduleId, seatIds);
    }

    // 좌석이 하나라도 읽혔으면 그 회차를 쓰고, 비었으면 회차를 따로 읽어 없는 회차(404)를 가린다(드문 경우에만 왕복 1번 추가)
    static Schedule scheduleOf(SeatHoldInventoryRepository inventory, Long scheduleId, List<ScheduleSeat> seats) {
        if (!seats.isEmpty()) {
            return seats.getFirst().getSchedule();
        }
        return inventory.findSchedule(scheduleId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }

    // 다른 회차의 좌석이나 없는 좌석 ID가 섞이면 조회 결과가 요청보다 적다
    static void requireAllSeatsOfSchedule(List<ScheduleSeat> seats, List<Long> seatIds) {
        if (seats.size() != seatIds.size()) {
            throw new BusinessException(SeatHoldErrorCode.SEAT_COUNT_MISMATCH);
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
}
