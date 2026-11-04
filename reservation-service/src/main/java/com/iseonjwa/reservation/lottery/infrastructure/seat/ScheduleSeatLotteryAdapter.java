package com.iseonjwa.reservation.lottery.infrastructure.seat;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository;
import com.iseonjwa.reservation.lottery.application.LotterySeatPort;
import com.iseonjwa.reservation.lottery.domain.LotterySeat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

// 좌석 SQL은 좌석을 가진 concert 모듈에 두고, 추첨은 이 어댑터로만 부른다.
// 서비스를 나누면 이 클래스를 내부 API 클라이언트로 바꾼다
@Component
@RequiredArgsConstructor
public class ScheduleSeatLotteryAdapter implements LotterySeatPort {

    private final ScheduleSeatRepository scheduleSeatRepository;

    @Override
    public int convertToLottery(Long scheduleId, SeatGrade sourceGrade, int seatCount, Long actorId) {
        return scheduleSeatRepository.convertToLottery(scheduleId, sourceGrade, seatCount, actorId);
    }

    @Override
    public int revertFromLottery(Long scheduleId, SeatGrade sourceGrade, int seatCount, Long actorId) {
        return scheduleSeatRepository.revertFromLottery(scheduleId, sourceGrade, seatCount, actorId);
    }

    @Override
    public int revertAllFromLottery(Long scheduleId, SeatGrade sourceGrade, Long actorId) {
        return scheduleSeatRepository.revertAllFromLottery(scheduleId, sourceGrade, actorId);
    }

    @Override
    public long countAllLotterySeats() {
        return scheduleSeatRepository.countAllLotterySeats();
    }

    @Override
    public List<LotterySeat> findLotterySeats(Long scheduleId) {
        return toSeats(scheduleSeatRepository.findLotterySeats(scheduleId));
    }

    @Override
    public List<LotterySeat> findAvailableLotterySeats(Long scheduleId) {
        return toSeats(scheduleSeatRepository.findAvailableLotterySeats(scheduleId));
    }

    private static List<LotterySeat> toSeats(List<ScheduleSeatRepository.LotterySeatRow> rows) {
        return rows.stream()
                .map(row -> new LotterySeat(row.seatId(), row.section(), row.rowNo(), row.seatNo()))
                .toList();
    }
}
