package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.common.persistence.CurrentActorProvider;
import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
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
 * 운영자의 추첨 설정·수정으로, 추첨 행 변경과 추첨석 전환을 한 트랜잭션으로 묶어 좌석이 모자라면 추첨도 바뀌지 않는다(D9, details.md 2절).
 */
@Service
public class LotteryAdminService {

    private final LotteryRepository lotteryRepository;
    // 회차 읽기는 모듈 규칙 `#14`에 따라 직접 쓰고, 좌석 쓰기는 포트로만 한다
    private final ScheduleRepository scheduleRepository;
    private final LotterySeatPort lotterySeatPort;
    private final CurrentActorProvider currentActorProvider;
    private final Clock clock;

    public LotteryAdminService(LotteryRepository lotteryRepository,
                               ScheduleRepository scheduleRepository,
                               LotterySeatPort lotterySeatPort,
                               CurrentActorProvider currentActorProvider,
                               @Qualifier("commonAuditingClock") Clock clock) {
        this.lotteryRepository = lotteryRepository;
        this.scheduleRepository = scheduleRepository;
        this.lotterySeatPort = lotterySeatPort;
        this.currentActorProvider = currentActorProvider;
        this.clock = clock;
    }

    // Note: 회차 행을 FOR UPDATE로 잡아 같은 회차의 설정·수정·좌석 생성을 줄 세우므로 중복 확인과 INSERT 사이에 다른 설정이 끼지 않는다.
    @Transactional
    public LotteryResult create(CreateLotteryCommand command) {
        Long scheduleId = command.scheduleId();
        LotterySettings settings = command.settings();

        Schedule schedule = findScheduleForUpdate(scheduleId);
        if (lotteryRepository.existsByScheduleId(scheduleId)) {
            throw new BusinessException(LotteryErrorCode.LOTTERY_ALREADY_EXISTS);
        }

        Lottery lottery = Lottery.create(scheduleId, settings, scheduleTimes(schedule), Instant.now(clock));
        convert(scheduleId, settings.sourceGrade(), settings.seatCount());

        return LotteryResult.of(lotteryRepository.save(lottery));
    }

    // 먼저 끝난 다른 수정의 결과 위에서 고치도록 설정과 같이 회차를 잠근 뒤에 추첨을 읽는다
    @Transactional
    public LotteryResult update(UpdateLotteryCommand command) {
        Long scheduleId = lotteryRepository.findScheduleIdById(command.lotteryId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        Schedule schedule = findScheduleForUpdate(scheduleId);
        Lottery lottery = lotteryRepository.findById(command.lotteryId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));

        LotterySettings before = lottery.settings();
        LotterySettings after = command.mergeInto(before);
        lottery.update(after, scheduleTimes(schedule), Instant.now(clock));
        adjustSeats(scheduleId, before, after);

        return LotteryResult.of(lottery);
    }

    private Schedule findScheduleForUpdate(Long scheduleId) {
        return scheduleRepository.findByIdForUpdate(scheduleId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }

    // 등급이 바뀌면 전부 되돌린 뒤 새 등급에서 다시 떼고, 같은 등급이면 차이만큼 늘리거나 줄인다(D9)
    private void adjustSeats(Long scheduleId, LotterySettings before, LotterySettings after) {
        if (before.sourceGrade() != after.sourceGrade()) {
            revert(scheduleId, before.sourceGrade(), before.seatCount());
            convert(scheduleId, after.sourceGrade(), after.seatCount());
        } else if (after.seatCount() > before.seatCount()) {
            convert(scheduleId, after.sourceGrade(), after.seatCount() - before.seatCount());
        } else if (after.seatCount() < before.seatCount()) {
            revert(scheduleId, before.sourceGrade(), before.seatCount() - after.seatCount());
        }
    }

    private void convert(Long scheduleId, SeatGrade sourceGrade, int count) {
        int converted = lotterySeatPort.convertToLottery(scheduleId, sourceGrade, count, actorId());

        // 이미 바꾼 좌석은 예외로 트랜잭션이 롤백되며 원래 등급으로 돌아간다
        if (converted < count) {
            throw new BusinessException(LotteryErrorCode.LOTTERY_SEATS_NOT_ENOUGH);
        }
    }

    private void revert(Long scheduleId, SeatGrade sourceGrade, int count) {
        int reverted = lotterySeatPort.revertFromLottery(scheduleId, sourceGrade, count, actorId());

        // 응모 시작 전에는 추첨석을 잡을 수 없어 모두 남아 있어야 하므로, 모자라면 데이터가 어긋난 것이다
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
