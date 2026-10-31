package com.iseonjwa.reservation.concert.domain.entity;

import com.iseonjwa.common.persistence.BaseEntity;
import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.global.vo.Money;
import com.iseonjwa.reservation.global.vo.MoneyConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

@Entity
@Table(name = "schedule_seats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScheduleSeat extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Schedule schedule;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private VenueSeat venueSeat;

    // 구역별 조회와 접수형 파티션 키로 쓰려고 venue_seats 값을 복사해 둔다.
    @Column(nullable = false, length = 10)
    private String section;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SeatGrade grade;

    @Convert(converter = MoneyConverter.class)
    @Column(nullable = false, precision = 15, scale = 0)
    private Money price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ScheduleSeatStatus status;

    // Note: FK는 "회차의 공연장 좌석인가"까지는 막지 못한다. 다른 공연장 좌석이 섞이지 않게 여기서 확인한다.
    public static ScheduleSeat create(Schedule schedule, VenueSeat venueSeat, Money price) {
        if (!isSameVenue(schedule.getVenue(), venueSeat.getVenue())) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        var seat = new ScheduleSeat();
        seat.schedule = schedule;
        seat.venueSeat = venueSeat;
        seat.section = venueSeat.getSection();
        seat.grade = venueSeat.getGrade();
        seat.price = Objects.requireNonNull(price);
        seat.status = ScheduleSeatStatus.AVAILABLE;
        return seat;
    }

    // 저장 전에는 id가 없으므로 같은 객체인지 보고, 저장된 뒤에는 id로 본다(지연 로딩 프록시는 다른 객체라서).
    private static boolean isSameVenue(Venue a, Venue b) {
        return a == b || (a.getId() != null && a.getId().equals(b.getId()));
    }
}
