package com.iseonjwa.reservation.concert.domain.entity;

import com.iseonjwa.common.persistence.BaseEntity;
import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

// Note: 지운 공연장·공연·회차는 연관을 따라가도 로드되지 않는다(EntityNotFoundException, SoftDeleteFilterTest).
// 좌석·예매가 참조하는 행은 삭제 기능에서 지우지 못하게 막는다.
@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(name = "schedules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Schedule extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Concert concert;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Venue venue;

    @Column(nullable = false)
    private Instant startsAt;

    @Column(nullable = false)
    private Instant openAt;

    @Column(nullable = false)
    private short maxPerUser;

    @Column(nullable = false)
    private short holdMinutes;

    @Version
    @Column(nullable = false)
    private Long version;

    // SA 12-3 기본값. JPA는 INSERT에 컬럼을 모두 넣어 DB DEFAULT가 쓰이지 않으므로 여기서 채운다.
    public static final int DEFAULT_MAX_PER_USER = 4;
    public static final int DEFAULT_HOLD_MINUTES = 5;

    // 형식(값이 있는가)은 요청 DTO가 검사하고, 값끼리의 관계는 어떤 경로로 만들어지든 여기서 지킨다.
    // 매수 한도와 선점 시간은 null이면 기본값을 쓴다.
    public static Schedule create(Concert concert, Venue venue, Instant startsAt, Instant openAt,
                                  Integer maxPerUser, Integer holdMinutes) {
        if (maxPerUser == null) {
            maxPerUser = DEFAULT_MAX_PER_USER;
        }
        if (holdMinutes == null) {
            holdMinutes = DEFAULT_HOLD_MINUTES;
        }
        if (!openAt.isBefore(startsAt)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (maxPerUser < 1 || maxPerUser > Short.MAX_VALUE || holdMinutes < 1 || holdMinutes > Short.MAX_VALUE) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        var schedule = new Schedule();
        schedule.concert = concert;
        schedule.venue = venue;
        schedule.startsAt = startsAt;
        schedule.openAt = openAt;
        schedule.maxPerUser = maxPerUser.shortValue();
        schedule.holdMinutes = holdMinutes.shortValue();
        return schedule;
    }
}
