package com.iseonjwa.reservation.concert.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;
import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository;
import com.iseonjwa.reservation.global.vo.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class JpaScheduleSeatRepository implements ScheduleSeatRepository {

    private final SpringDataScheduleSeatRepository springDataScheduleSeatRepository;

    @Override
    public boolean existsByScheduleId(Long scheduleId) {
        return springDataScheduleSeatRepository.existsByScheduleId(scheduleId);
    }

    @Override
    public List<ScheduleSeat> findByScheduleIdWithVenueSeat(Long scheduleId) {
        return springDataScheduleSeatRepository.findByScheduleIdWithVenueSeat(scheduleId);
    }

    @Override
    public List<SeatMapRow> findSeatMapRows(Long scheduleId) {
        return springDataScheduleSeatRepository.findSeatMapRows(scheduleId).stream()
                .map(row -> new SeatMapRow(toLayout(row), (Boolean) row[6]))
                .toList();
    }

    @Override
    public int createFromVenueSeats(Long scheduleId, Long venueId, Map<SeatGrade, Money> prices, Long actorId) {
        return springDataScheduleSeatRepository.insertFromVenueSeats(scheduleId, venueId,
                priceOf(prices, SeatGrade.VIP), priceOf(prices, SeatGrade.R), priceOf(prices, SeatGrade.S), actorId);
    }

    @Override
    public int convertToLottery(Long scheduleId, SeatGrade sourceGrade, int seatCount, Long actorId) {
        return springDataScheduleSeatRepository.updateGradeToLottery(scheduleId, sourceGrade.name(), seatCount,
                actorId);
    }

    @Override
    public int revertFromLottery(Long scheduleId, SeatGrade sourceGrade, int seatCount, Long actorId) {
        return springDataScheduleSeatRepository.updateGradeFromLottery(scheduleId, sourceGrade.name(), seatCount,
                actorId);
    }

    @Override
    public int revertAllFromLottery(Long scheduleId, SeatGrade sourceGrade, Long actorId) {
        return springDataScheduleSeatRepository.updateAllGradeFromLottery(scheduleId, sourceGrade.name(), actorId);
    }

    @Override
    public long countAllLotterySeats() {
        return springDataScheduleSeatRepository.countAllLotterySeats();
    }

    @Override
    public List<LotterySeatRow> findLotterySeats(Long scheduleId) {
        return toRows(springDataScheduleSeatRepository.findLotterySeatRows(scheduleId));
    }

    @Override
    public List<LotterySeatRow> findAvailableLotterySeats(Long scheduleId) {
        return toRows(springDataScheduleSeatRepository.findAvailableLotterySeatRows(scheduleId));
    }

    @Override
    public int sellHeldSeats(Long scheduleId, List<Long> seatIds, Instant now) {
        return updateHeldStatus(scheduleId, seatIds, "SOLD", now);
    }

    @Override
    public int releaseHeldSeats(Long scheduleId, List<Long> seatIds, Instant now) {
        return updateHeldStatus(scheduleId, seatIds, "AVAILABLE", now);
    }

    // 빈 목록을 IN ()에 넘기면 SQL 문법 오류라 바꿀 좌석이 없으면 부르지 않는다
    private int updateHeldStatus(Long scheduleId, List<Long> seatIds, String toStatus, Instant now) {
        if (seatIds.isEmpty()) {
            return 0;
        }
        return springDataScheduleSeatRepository.updateHeldStatus(scheduleId, seatIds, toStatus, now);
    }

    private static List<LotterySeatRow> toRows(List<Object[]> rows) {
        return rows.stream()
                .map(row -> new LotterySeatRow(((Number) row[0]).longValue(), (String) row[1],
                        ((Number) row[2]).intValue(), ((Number) row[3]).intValue()))
                .toList();
    }

    // 열 순서는 findSeatMapRows의 SELECT 순서다
    private static SeatLayoutRow toLayout(Object[] row) {
        return new SeatLayoutRow(((Number) row[0]).longValue(), (String) row[1], SeatGrade.valueOf((String) row[2]),
                (BigDecimal) row[3], ((Number) row[4]).intValue(), ((Number) row[5]).intValue());
    }

    // 공연장에 없는 등급은 해당 좌석 행이 없어 쓰이지 않는다. SQL에 null을 넘기지 않으려고 0을 넣는다
    private static BigDecimal priceOf(Map<SeatGrade, Money> prices, SeatGrade grade) {
        return prices.getOrDefault(grade, Money.ZERO).toBigDecimal();
    }
}
