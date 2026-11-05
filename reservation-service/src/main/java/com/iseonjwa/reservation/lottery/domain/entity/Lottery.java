package com.iseonjwa.reservation.lottery.domain.entity;

import com.iseonjwa.common.persistence.BaseEntity;
import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
import com.iseonjwa.reservation.lottery.domain.LotteryErrorCode;
import com.iseonjwa.reservation.lottery.domain.policy.DrawRanker;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * 회차당 하나인 추첨으로, 서비스를 나눌 때 엔티티 연관을 끊지 않아도 되게 회차는 ID로만 참조한다(D2, D9).
 */
// Note: 지운 추첨도 UNIQUE(schedule_id)에 걸리므로 @SQLRestriction으로 거르면 중복 확인을 통과한 INSERT가 제약 위반 500이 난다.
@Entity
@Table(name = "lotteries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Lottery extends BaseEntity {
    public static final int MIN_PER_ENTRY = 1;
    public static final int MAX_PER_ENTRY = 2;
    // 공연 시작 전에 끝나야 하는, 추첨 뒤 결제·승계·종결까지의 최장 시간(D14)
    public static final Duration MAX_SETTLE_AFTER_DRAW = Duration.ofMinutes(60);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long scheduleId;

    // 추첨석을 떼어 온 등급으로, 종결 때 남은 추첨석을 이 등급으로 되돌린다
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SeatGrade sourceGrade;

    @Column(nullable = false)
    private int seatCount;

    @Column(nullable = false)
    private int waitlistCount;

    @Column(nullable = false)
    private short maxPerEntry;

    @Column(nullable = false)
    private Instant entryStartAt;

    @Column(nullable = false)
    private Instant entryEndAt;

    @Column(nullable = false)
    private Instant drawAt;

    // 설정 뒤 바꾸면 이미 계산할 수 있던 순위가 달라지므로 updatable = false로 엔티티 수정을 막는다(D7)
    @Column(nullable = false, updatable = false)
    private byte[] seed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private LotteryStatus status;

    /**
     * 어떤 경로로 만들어지든 값끼리의 관계를 여기서 지키며 추첨을 설정한다.
     *
     * @param schedule 회차의 일반 예매 오픈 시각과 공연 시작 시각
     * @param now      설정 요청 시각
     * @throws BusinessException 값 관계가 맞지 않으면 {@code VALIDATION_FAILED},
     *                           일반 오픈이나 응모 시작이 지났으면 {@code LOTTERY_SETUP_CLOSED}
     */
    public static Lottery create(Long scheduleId, LotterySettings settings, ScheduleTimes schedule, Instant now) {
        settings.validate(schedule);
        requireBeforeSetupDeadline(schedule.openAt(), settings.entryStartAt(), now);
        Lottery lottery = new Lottery();
        lottery.scheduleId = scheduleId;
        lottery.apply(settings);
        lottery.seed = DrawRanker.newSeed();
        lottery.status = LotteryStatus.OPEN;
        return lottery;
    }

    /**
     * 시드와 회차를 유지한 채 추첨 설정을 고친다(D9).
     *
     * @throws BusinessException 지금 설정이나 새 설정의 응모 시작·일반 오픈이 지났으면 {@code LOTTERY_SETUP_CLOSED}
     */
    public void update(LotterySettings settings, ScheduleTimes schedule, Instant now) {
        // 이미 시작한 응모는 새 시작 시각을 미래로 미뤄도 고치지 못하게 지금 값으로 먼저 확인한다
        requireBeforeSetupDeadline(schedule.openAt(), entryStartAt, now);
        settings.validate(schedule);
        requireBeforeSetupDeadline(schedule.openAt(), settings.entryStartAt(), now);
        apply(settings);
    }

    public LotterySettings settings() {
        return new LotterySettings(
                sourceGrade,
                seatCount,
                waitlistCount,
                maxPerEntry,
                entryStartAt,
                entryEndAt,
                drawAt);
    }

    // 순위 계산 입력이라 바깥에서 배열을 바꾸지 못하게 복사본을 준다
    public byte[] getSeed() {
        return seed.clone();
    }

    // 일반 오픈 뒤에 좌석을 떼면 이미 선점·판매된 좌석과 다투고, 응모 시작 뒤에 바꾸면 응모 조건이 달라진다(D9 후속)
    private static void requireBeforeSetupDeadline(Instant scheduleOpenAt, Instant entryStartAt, Instant now) {
        if (!now.isBefore(scheduleOpenAt) || !now.isBefore(entryStartAt)) {
            throw new BusinessException(LotteryErrorCode.LOTTERY_SETUP_CLOSED);
        }
    }

    private void apply(LotterySettings settings) {
        sourceGrade = settings.sourceGrade();
        seatCount = settings.seatCount();
        waitlistCount = settings.waitlistCount();
        maxPerEntry = (short) settings.maxPerEntry();
        entryStartAt = settings.entryStartAt();
        entryEndAt = settings.entryEndAt();
        drawAt = settings.drawAt();
    }

    /**
     * 설정과 수정이 같은 규칙으로 검사하는, 운영자가 정하는 추첨 설정값.
     */
    public record LotterySettings(SeatGrade sourceGrade,
                                  int seatCount,
                                  int waitlistCount,
                                  int maxPerEntry,
                                  Instant entryStartAt,
                                  Instant entryEndAt,
                                  Instant drawAt) {

        void validate(ScheduleTimes schedule) {
            if (sourceGrade == SeatGrade.LOTTERY
                    || seatCount < 1
                    || waitlistCount < 0
                    || maxPerEntry < MIN_PER_ENTRY
                    || maxPerEntry > MAX_PER_ENTRY
                    || !entryStartAt.isBefore(entryEndAt)
                    || entryEndAt.isAfter(drawAt)
                    || drawAt.plus(MAX_SETTLE_AFTER_DRAW).isAfter(schedule.startsAt())) {
                throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
            }
        }
    }

    /**
     * 모듈 경계를 지키려고 회차 엔티티 대신 값만 받는, 설정 검사에 필요한 회차 시각.
     */
    public record ScheduleTimes(Instant openAt, Instant startsAt) {
    }
}
