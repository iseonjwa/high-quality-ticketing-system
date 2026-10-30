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
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

// Note: 지운 공연장·공연·회차는 연관을 따라가도 로드되지 않는다(EntityNotFoundException, SoftDeleteFilterTest).
// 좌석·예매가 참조하는 행은 삭제 기능에서 지우지 못하게 막는다.
@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(name = "schedules")
public class Schedule extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "concert_id", nullable = false)
    private Concert concert;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "open_at", nullable = false)
    private Instant openAt;

    @Column(name = "max_per_user", nullable = false)
    private short maxPerUser;

    @Column(name = "hold_minutes", nullable = false)
    private short holdMinutes;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Schedule() {}

    // 형식(값이 있는가)은 요청 DTO가 검사하고, 값끼리의 관계는 어떤 경로로 만들어지든 여기서 지킨다.
    public static Schedule create(Concert concert, Venue venue, Instant startsAt, Instant openAt,
                                  int maxPerUser, int holdMinutes) {
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
        schedule.maxPerUser = (short) maxPerUser;
        schedule.holdMinutes = (short) holdMinutes;
        return schedule;
    }

    public Long getId() { return id; }
    public Concert getConcert() { return concert; }
    public Venue getVenue() { return venue; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getOpenAt() { return openAt; }
    public int getMaxPerUser() { return maxPerUser; }
    public int getHoldMinutes() { return holdMinutes; }
}
