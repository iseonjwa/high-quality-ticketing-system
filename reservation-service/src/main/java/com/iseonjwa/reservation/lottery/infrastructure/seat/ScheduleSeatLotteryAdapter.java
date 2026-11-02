package com.iseonjwa.reservation.lottery.infrastructure.seat;

import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository;
import com.iseonjwa.reservation.lottery.application.LotterySeatPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
}
