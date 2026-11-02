package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.common.persistence.CurrentActorProvider;
import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import com.iseonjwa.reservation.lottery.domain.Lottery;
import com.iseonjwa.reservation.lottery.domain.Lottery.LotterySettings;
import com.iseonjwa.reservation.lottery.domain.Lottery.ScheduleTimes;
import com.iseonjwa.reservation.lottery.domain.LotteryErrorCode;
import com.iseonjwa.reservation.lottery.domain.LotteryRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 운영자의 추첨 설정·수정(D9, details.md 2절). 추첨 행 변경과 추첨석 전환을 한 트랜잭션으로 묶어,
 * 좌석이 모자라면 추첨도 바뀌지 않는다.
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

    // Note: 회차 행을 FOR UPDATE로 잡아 같은 회차의 추첨 설정·수정·좌석 생성을 줄 세운다.
    // 그래서 "이미 있는가" 확인과 INSERT 사이에 다른 설정이 끼지 않고, UNIQUE(schedule_id)는 마지막 방어선으로만 남는다.
    @Transactional
    public LotteryResult create(CreateLotteryCommand command) {
        Schedule schedule = scheduleRepository.findByIdForUpdate(command.scheduleId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (lotteryRepository.existsByScheduleId(command.scheduleId())) {
            throw new BusinessException(LotteryErrorCode.LOTTERY_ALREADY_EXISTS);
        }

        Lottery lottery = Lottery.create(command.scheduleId(), command.settings(), scheduleTimes(schedule),
                Instant.now(clock));
        var settings = command.settings();
        convert(command.scheduleId(), settings, settings.seatCount());

        return LotteryResult.of(lotteryRepository.save(lottery));
    }

    // 회차 잠금은 설정과 같다. 잠근 뒤에 추첨을 읽어야 먼저 끝난 다른 수정의 결과 위에서 고친다
    @Transactional
    public LotteryResult update(UpdateLotteryCommand command) {
        Long scheduleId = lotteryRepository.findScheduleIdById(command.lotteryId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        Schedule schedule = scheduleRepository.findByIdForUpdate(scheduleId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        Lottery lottery = lotteryRepository.findById(command.lotteryId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));

        LotterySettings before = lottery.settings();
        LotterySettings after = command.mergeInto(before);
        lottery.update(after, scheduleTimes(schedule), Instant.now(clock));
        adjustSeats(scheduleId, before, after);

        return LotteryResult.of(lottery);
    }

    // 등급이 바뀌면 전부 되돌린 뒤 새 등급에서 다시 떼고, 같은 등급이면 차이만큼 늘리거나 줄인다(D9)
    private void adjustSeats(Long scheduleId, LotterySettings before, LotterySettings after) {
        if (before.sourceGrade() != after.sourceGrade()) {
            revert(scheduleId, before, before.seatCount());
            convert(scheduleId, after, after.seatCount());
        } else if (after.seatCount() > before.seatCount()) {
            convert(scheduleId, after, after.seatCount() - before.seatCount());
        } else if (after.seatCount() < before.seatCount()) {
            revert(scheduleId, before, before.seatCount() - after.seatCount());
        }
    }

    private void convert(Long scheduleId, LotterySettings settings, int count) {
        int converted = lotterySeatPort.convertToLottery(scheduleId, settings.sourceGrade(), count, actorId());
        if (converted < count) {
            // 이미 바꾼 좌석은 예외로 트랜잭션이 롤백되며 원래 등급으로 돌아간다
            throw new BusinessException(LotteryErrorCode.LOTTERY_SEATS_NOT_ENOUGH);
        }
    }

    private void revert(Long scheduleId, LotterySettings settings, int count) {
        int reverted = lotterySeatPort.revertFromLottery(scheduleId, settings.sourceGrade(), count, actorId());
        // 응모 시작 전에는 추첨석을 잡을 수 없어 seatCount개가 모두 남아 있어야 한다. 모자라면 데이터가 어긋난 것이다
        if (reverted != count) {
            throw new IllegalStateException("추첨석 수가 설정과 다르다: scheduleId=" + scheduleId
                    + ", expected=" + count + ", reverted=" + reverted);
        }
    }

    private Long actorId() {
        return currentActorProvider.currentActorId().orElse(null);
    }

    private static ScheduleTimes scheduleTimes(Schedule schedule) {
        return new ScheduleTimes(schedule.getOpenAt(), schedule.getStartsAt());
    }
}
