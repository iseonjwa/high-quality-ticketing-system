package com.iseonjwa.reservation.concert.infrastructure.persistence;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository;
import com.iseonjwa.reservation.global.vo.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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
    public int createFromVenueSeats(Long scheduleId, Long venueId, Map<SeatGrade, Money> prices, Long actorId) {
        return springDataScheduleSeatRepository.insertFromVenueSeats(scheduleId, venueId,
                priceOf(prices, SeatGrade.VIP), priceOf(prices, SeatGrade.R), priceOf(prices, SeatGrade.S), actorId);
    }

    // 공연장에 없는 등급은 해당 좌석 행이 없어 쓰이지 않는다. SQL에 null을 넘기지 않으려고 0을 넣는다
    private static BigDecimal priceOf(Map<SeatGrade, Money> prices, SeatGrade grade) {
        return prices.getOrDefault(grade, Money.ZERO).toBigDecimal();
    }
}
