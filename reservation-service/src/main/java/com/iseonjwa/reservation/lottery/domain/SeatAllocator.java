package com.iseonjwa.reservation.lottery.domain;

import com.iseonjwa.reservation.lottery.domain.LotterySeat.SeatPosition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 추첨 당첨·좌석 배정을 계산만 하며, DB 쓰기·행 잠금·재실행 방지는 호출하는 쪽이 맡는다(D7, D9).
 * <ul>
 *   <li>당첨: 순위대로 내려가며 남은 좌석이 매수 이상이면 당첨하고, 모자라면 뒤의 1매 응모자를 막지 않게 건너뛴다.</li>
 *   <li>좌석: 당첨 순서대로 ID가 작은 좌석부터 주고, 2매는 옆자리 쌍을 먼저 찾되 연석을 보장하지는 않는다(D7).</li>
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
     * @param waitlistLimit  운영자가 설정한 예비자 수 상한({@code lotteries.waitlist_count})
     */
    // Note: 건너뛴 2매 응모자를 탈락시키면 승계 때 더 낮은 순위가 먼저 받는 역전이 생기므로 예비자에 순위대로 남긴다(11/1 결정, options.md D7).
    public static DrawAllocation draw(List<DrawEntrant> rankedEntrants,
                                      List<LotterySeat> lotterySeats,
                                      int waitlistLimit) {
        if (waitlistLimit < 0) {
            throw new IllegalArgumentException("예비자 수는 0 이상이다: " + waitlistLimit);
        }

        RemainingSeats seats = new RemainingSeats(lotterySeats);
        List<SeatAssignment> winners = new ArrayList<>();
        List<DrawEntrant> waitlisted = new ArrayList<>();
        List<DrawEntrant> lost = new ArrayList<>();

        // 남은 응모를 예비자·탈락으로 나눠야 하므로 좌석이 다 떨어진 뒤에도 끝까지 돈다.
        for (DrawEntrant entrant : rankedEntrants) {
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
     * 남은 좌석에 맞는 응모에 순위대로 좌석을 정하며, 운영 코드에서는 배정 워커가 당첨자 한 명의 좌석을 고를 때 쓴다(D9, D10).
     *
     * @param entrantsInRankOrder 좌석을 정할 응모를 순위 순으로
     * @param freeSeats           아직 잡히지 않은 추첨석
     * @return 좌석을 받은 응모와 그 좌석(순위 순). 매수가 남은 좌석보다 많은 응모는 빠진다
     */
    public static List<SeatAssignment> succeed(List<DrawEntrant> entrantsInRankOrder, List<LotterySeat> freeSeats) {
        RemainingSeats seats = new RemainingSeats(freeSeats);
        List<SeatAssignment> winners = new ArrayList<>();

        for (DrawEntrant entrant : entrantsInRankOrder) {
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

    // 좌석 ID 순으로 들고 있다가 매수만큼 꺼내는, 아직 배정하지 않은 추첨석.
    private static final class RemainingSeats {
        // TreeMap은 키(좌석 ID) 순서를 유지하므로 ID가 가장 작은 좌석을 바로 꺼낼 수 있다.
        private final TreeMap<Long, LotterySeat> byId = new TreeMap<>();
        // 옆자리가 아직 남아 있는지 전체를 훑지 않고 O(1)에 확인하는 위치 색인.
        private final Map<SeatPosition, LotterySeat> byPosition = new HashMap<>();

        // 같은 좌석·같은 위치가 두 번 들어오면 한 자리를 두 사람에게 줄 수 있으므로 계산을 시작하기 전에 거절한다.
        RemainingSeats(List<LotterySeat> seats) {
            for (LotterySeat seat : seats) {
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

        // 호출하는 쪽이 count() >= quantity를 먼저 확인하고, 꺼낸 좌석은 다시 배정되지 않게 바로 지운다.
        List<LotterySeat> take(int quantity) {
            List<LotterySeat> picked = quantity == 1
                    ? List.of(byId.firstEntry().getValue())
                    : pickPair();

            for (LotterySeat seat : picked) {
                byId.remove(seat.seatId());
                byPosition.remove(seat.position());
            }
            return picked;
        }

        // 옆자리가 남은 첫 좌석과 ID가 작은 쪽 옆자리를 주고, 쌍이 하나도 없으면 ID가 가장 작은 두 좌석을 준다.
        // Note: 추첨은 상한 10초인 한 트랜잭션에서 돌므로(D8) 위치 색인으로 2매 당첨자 m명 기준 최악 O(m·n²)을 O(m·n)으로 줄였다.
        private List<LotterySeat> pickPair() {
            for (LotterySeat seat : byId.values()) {
                Optional<LotterySeat> neighbor = seat.neighborPositions().stream()
                        .map(byPosition::get)
                        .filter(Objects::nonNull)
                        .min(Comparator.comparingLong(LotterySeat::seatId));
                if (neighbor.isPresent()) {
                    return List.of(seat, neighbor.get());
                }
            }

            Iterator<LotterySeat> seatsById = byId.values().iterator();
            return List.of(seatsById.next(), seatsById.next());
        }
    }

    /**
     * 당첨된 응모 하나와 배정된 좌석(1~2석).
     */
    public record SeatAssignment(DrawEntrant entrant, List<LotterySeat> seats) {
        public SeatAssignment {
            seats = List.copyOf(seats);
        }
    }

    /**
     * 세 목록 모두 순위 순이고 한 응모는 셋 중 한 곳에만 들어가는 추첨 결과.
     *
     * @param winners    당첨(WON)과 배정 좌석
     * @param waitlisted 앞에 있을수록 승계 순번이 빠른 예비자(WAITLISTED)
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
