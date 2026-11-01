package com.iseonjwa.reservation.lottery.domain;

import com.iseonjwa.reservation.lottery.domain.LotterySeat.SeatPosition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 추첨 당첨·좌석 배정 계산(D7 배정 규칙, D9 좌석 규칙, D13 승계 매수 규칙).
 * <p>
 * 이 클래스는 계산만 한다. 결과를 DB에 쓰는 일, 추첨 행 잠금, 재실행 방지는 호출하는 쪽(추첨 스케줄러 ISE-87,
 * 승계 워커 ISE-93)이 맡는다.
 * <ul>
 *   <li>당첨: 순위대로 내려가며 남은 좌석이 매수 이상이면 당첨, 모자라면 건너뛰고 다음 순위를 본다.
 *       2매 응모자가 마지막 1석 때문에 멈춰 뒤의 1매 응모자까지 막지 않게 하려는 규칙이다.</li>
 *   <li>좌석: 당첨 순서대로, 남은 좌석 중 ID가 작은 것부터 준다. 2매는 옆자리 쌍을 먼저 찾고, 없으면 떨어진 두 좌석을 준다.
 *       연석은 보장하지 않으며 응모 규칙에 공개한다(D7).</li>
 *   <li>예비·탈락: 당첨되지 않은 응모 중 순위 상위 {@code waitlistLimit}명이 예비자, 나머지는 탈락이다.</li>
 * </ul>
 */
public final class SeatAllocator {

    private SeatAllocator() {}

    /**
     * 추첨 한 번의 결과를 계산한다.
     *
     * @param rankedEntrants 취소되지 않은 응모를 순위 순으로({@link DrawRanker#rank}의 결과)
     * @param lotterySeats   그 회차의 추첨석 전체
     * @param waitlistLimit  예비자 수 상한({@code lotteries.waitlist_count}, 기본 1,000)
     */
    // Note: 건너뛴 2매 응모자도 예비자에 순위대로 남긴다. 탈락시키면 승계 때 2석이 돌아와도 더 낮은 순위가 먼저 받는
    // 역전이 생긴다(11/1 결정, options.md D7, 학습 노트 2026-11-01-lottery-skipped-entrant-waitlist).
    public static DrawAllocation draw(List<DrawEntrant> rankedEntrants, List<LotterySeat> lotterySeats, int waitlistLimit) {
        if (waitlistLimit < 0) {
            throw new IllegalArgumentException("예비자 수는 0 이상이다: " + waitlistLimit);
        }
        var seats = new RemainingSeats(lotterySeats);
        var winners = new ArrayList<SeatAssignment>();
        var waitlisted = new ArrayList<DrawEntrant>();
        var lost = new ArrayList<DrawEntrant>();

        // 좌석이 다 떨어진 뒤에도 끝까지 돈다. 남은 응모를 예비자·탈락으로 나눠야 하기 때문이다.
        for (var entrant : rankedEntrants) {
            if (seats.count() >= entrant.quantity()) {
                winners.add(new SeatAssignment(entrant, seats.take(entrant.quantity())));
            } else if (waitlisted.size() < waitlistLimit) {
                waitlisted.add(entrant);
            } else {
                lost.add(entrant);
            }
        }
        return new DrawAllocation(winners, waitlisted, lost);
    }

    /**
     * 승계 계산(D13). 돌아온 좌석에 맞는 예비자를 순위대로 당첨시킨다.
     * <p>
     * 매수가 남은 좌석보다 많은 예비자는 건너뛰고, 결과에 넣지 않는다. 그 응모는 그대로 WAITLISTED로 남아
     * 다음 승계 때 다시 기회를 얻는다. 예: 2석이 돌아왔는데 첫 예비자가 1매면, 남은 1석에 맞는 다음 1매 예비자를 찾는다.
     *
     * @param waitlistInRankOrder 아직 WAITLISTED인 응모를 순위 순으로
     * @param freeSeats           결제 실패·선점 만료 등으로 비어 있는 추첨석
     * @return 새로 당첨된 응모와 그 좌석. 순위 순이다
     */
    public static List<SeatAssignment> succeed(List<DrawEntrant> waitlistInRankOrder, List<LotterySeat> freeSeats) {
        var seats = new RemainingSeats(freeSeats);
        var winners = new ArrayList<SeatAssignment>();
        for (var entrant : waitlistInRankOrder) {
            // draw와 달리 예비·탈락을 나눌 필요가 없으므로 좌석이 떨어지면 바로 멈춘다.
            if (seats.count() == 0) {
                break;
            }
            if (seats.count() >= entrant.quantity()) {
                winners.add(new SeatAssignment(entrant, seats.take(entrant.quantity())));
            }
        }
        return winners;
    }

    // 아직 배정하지 않은 추첨석. 좌석 ID 순으로 들고 있다가 매수만큼 꺼낸다.
    private static final class RemainingSeats {
        // TreeMap은 키(좌석 ID) 순서를 유지하므로 "ID가 가장 작은 좌석"을 바로 꺼낼 수 있다.
        private final TreeMap<Long, LotterySeat> byId = new TreeMap<>();
        // 위치로 남은 좌석을 찾는 색인. 옆자리가 아직 남아 있는지 전체를 훑지 않고 O(1)에 확인한다.
        private final Map<SeatPosition, LotterySeat> byPosition = new HashMap<>();

        // 같은 좌석·같은 위치가 두 번 들어오면 한 자리를 두 사람에게 줄 수 있으므로 계산을 시작하기 전에 거절한다.
        RemainingSeats(List<LotterySeat> seats) {
            for (var seat : seats) {
                if (byId.put(seat.seatId(), seat) != null) {
                    throw new IllegalArgumentException("같은 좌석이 두 번 들어왔다: " + seat.seatId());
                }
                if (byPosition.put(seat.position(), seat) != null) {
                    throw new IllegalArgumentException("같은 위치의 좌석이 두 번 들어왔다: " + seat.position());
                }
            }
        }

        int count() {
            return byId.size();
        }

        // 호출하는 쪽이 count() >= quantity를 먼저 확인한다. 꺼낸 좌석은 다시 배정되지 않게 바로 지운다.
        List<LotterySeat> take(int quantity) {
            var picked = quantity == 1 ? List.of(byId.firstEntry().getValue()) : pickPair();
            for (var seat : picked) {
                byId.remove(seat.seatId());
                byPosition.remove(seat.position());
            }
            return picked;
        }

        // 2매 배정. ID가 가장 작은 좌석부터 보며 옆자리가 아직 남아 있는 첫 좌석과 그 옆자리를 준다.
        // 양쪽 옆자리가 다 남아 있으면 ID가 작은 쪽을 고른다. 쌍이 하나도 없으면 ID가 가장 작은 두 좌석을 준다.
        // Note: 옆자리는 위치 색인으로 찾으므로 한 번 호출에 최악 O(n)(남은 좌석을 한 번 훑음), 2매 당첨자 m명이면 O(m·n)이다.
        // 처음에는 좌석마다 남은 좌석 전체를 다시 훑어 O(m·n²)이었다. 추첨은 상한 10초인 한 트랜잭션 안에서 돌므로(D8) 줄였다.
        private List<LotterySeat> pickPair() {
            for (var seat : byId.values()) {
                var neighbor = seat.neighborPositions().stream()
                        .map(byPosition::get)
                        .filter(Objects::nonNull)
                        .min(Comparator.comparingLong(LotterySeat::seatId));
                if (neighbor.isPresent()) {
                    return List.of(seat, neighbor.get());
                }
            }
            var ids = byId.values().iterator();
            return List.of(ids.next(), ids.next());
        }
    }

    /**
     * 당첨된 응모 하나와 배정된 좌석(1~2석, 좌석 ID 작은 쪽이 앞).
     */
    public record SeatAssignment(DrawEntrant entrant, List<LotterySeat> seats) {
        public SeatAssignment {
            seats = List.copyOf(seats);
        }
    }

    /**
     * 추첨 결과. 세 목록 모두 순위 순이며, 한 응모는 셋 중 한 곳에만 들어간다.
     *
     * @param winners    당첨(WON)과 배정 좌석
     * @param waitlisted 예비자(WAITLISTED). 앞에 있을수록 승계 순번이 빠르다
     * @param lost       탈락(LOST)
     */
    public record DrawAllocation(List<SeatAssignment> winners, List<DrawEntrant> waitlisted, List<DrawEntrant> lost) {
        public DrawAllocation {
            winners = List.copyOf(winners);
            waitlisted = List.copyOf(waitlisted);
            lost = List.copyOf(lost);
        }
    }
}
