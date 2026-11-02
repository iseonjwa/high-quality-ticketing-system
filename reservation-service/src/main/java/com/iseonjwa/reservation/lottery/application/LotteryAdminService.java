package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.common.persistence.CurrentActorProvider;
import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import com.iseonjwa.reservation.lottery.domain.DrawTimeWindow;
import com.iseonjwa.reservation.lottery.domain.Lottery;
import com.iseonjwa.reservation.lottery.domain.Lottery.ScheduleTimes;
import com.iseonjwa.reservation.lottery.domain.LotteryErrorCode;
import com.iseonjwa.reservation.lottery.domain.LotteryRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 운영자의 추첨 설정(D9, details.md 2절). 추첨 행 저장과 추첨석 전환을 한 트랜잭션으로 묶어,
 * 좌석이 모자라면 추첨도 만들지 않는다.
 */
@Service
public class LotteryAdminService {

    private final LotteryRepository lotteryRepository;
    // 회차 읽기는 모듈 규칙 #14의 "concert 조회는 읽기 직접 허용"에 따라 직접 쓴다. 좌석 쓰기는 포트로만 한다
    private final ScheduleRepository scheduleRepository;
    private final LotterySeatPort lotterySeatPort;
    private final CurrentActorProvider currentActorProvider;
    private final Clock clock;

    public LotteryAdminService(LotteryRepository lotteryRepository, ScheduleRepository scheduleRepository,
                               LotterySeatPort lotterySeatPort, CurrentActorProvider currentActorProvider,
                               @Qualifier("commonAuditingClock") Clock clock) {
        this.lotteryRepository = lotteryRepository;
        this.scheduleRepository = scheduleRepository;
        this.lotterySeatPort = lotterySeatPort;
        this.currentActorProvider = currentActorProvider;
        this.clock = clock;
    }

    // Note: 회차 행을 FOR UPDATE로 잡아 같은 회차의 추첨 설정·좌석 생성을 줄 세운다.
    // 그래서 "이미 있는가" 확인과 INSERT 사이에 다른 설정이 끼지 않고, UNIQUE(schedule_id)는 마지막 방어선으로만 남는다.
    @Transactional
    public LotteryCreateResult create(CreateLotteryCommand command) {
        Schedule schedule = scheduleRepository.findByIdForUpdate(command.scheduleId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (lotteryRepository.existsByScheduleId(command.scheduleId())) {
            throw new BusinessException(LotteryErrorCode.LOTTERY_ALREADY_EXISTS);
        }

        Lottery lottery = Lottery.create(command.scheduleId(), command.settings(), scheduleTimes(schedule),
                Instant.now(clock));

        Long actorId = currentActorProvider.currentActorId().orElse(null);
        var settings = command.settings();
        int converted = lotterySeatPort.convertToLottery(command.scheduleId(), settings.sourceGrade(),
                settings.seatCount(), actorId);
        if (converted < settings.seatCount()) {
            // 이미 바꾼 좌석은 예외로 트랜잭션이 롤백되며 원래 등급으로 돌아간다
            throw new BusinessException(LotteryErrorCode.LOTTERY_SEATS_NOT_ENOUGH);
        }

        Lottery saved = lotteryRepository.save(lottery);
        List<String> warnings = DrawTimeWindow.contains(saved.getDrawAt())
                ? List.of()
                : List.of(LotteryCreateResult.DRAW_AT_OUTSIDE_LOW_TRAFFIC_WINDOW);
        return new LotteryCreateResult(saved.getId(), saved.getScheduleId(), saved.getSourceGrade(),
                saved.getSeatCount(), saved.getStatus(), warnings);
    }

    private static ScheduleTimes scheduleTimes(Schedule schedule) {
        return new ScheduleTimes(schedule.getOpenAt(), schedule.getStartsAt());
    }
}
