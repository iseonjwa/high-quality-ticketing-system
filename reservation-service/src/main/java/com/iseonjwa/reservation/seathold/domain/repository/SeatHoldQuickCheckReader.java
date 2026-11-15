package com.iseonjwa.reservation.seathold.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 동기형 선점의 빠른 거절 판정 재료를 쓰기 트랜잭션 밖에서 한 문장으로 읽는다(DB 왕복 1번, 롤백 없음).
 * 정상 피크 응답 대부분이 409라, 거절할 요청은 트랜잭션을 열지 않고 끝내려는 것이다(seathold-perf-loop.md 반복 5).
 * 통과한 요청은 쓰기 트랜잭션에서 지금처럼 다시 판정하므로, 이 결과는 거절에만 쓴다.
 */
public interface SeatHoldQuickCheckReader {

    QuickCheck read(UUID requestId, String entryTokenJti, Long scheduleId, List<Long> seatIds);

    /**
     * @param scheduleExists 지운 회차는 없는 것으로 본다
     * @param seatsFound     요청 좌석 중 이 회차에 속한 좌석 수
     * @param seatsHoldable  그중 선점 가능한(AVAILABLE이고 추첨석이 아닌) 좌석 수
     */
    record QuickCheck(boolean requestExists, boolean tokenActive, boolean scheduleExists, int maxPerUser,
            Instant openAt, Instant startsAt, int seatsFound, int seatsHoldable) {}
}
