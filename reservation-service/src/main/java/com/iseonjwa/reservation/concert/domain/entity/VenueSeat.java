package com.iseonjwa.reservation.concert.domain.entity;

import com.iseonjwa.common.persistence.BaseEntity;
import jakarta.persistence.Column;
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

@Entity
@Table(name = "venue_seats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VenueSeat extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Venue venue;

    @Column(nullable = false, length = 10)
    private String section;

    @Column(nullable = false)
    private int rowNo;

    @Column(nullable = false)
    private int seatNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SeatGrade grade;

    // 추첨석은 회차마다 따로 정하므로 공연장 배치에는 LOTTERY가 올 수 없다
    public VenueSeat(Venue venue, String section, int rowNo, int seatNo, SeatGrade grade) {
        if (grade == SeatGrade.LOTTERY) {
            throw new IllegalArgumentException("공연장 좌석 등급에 LOTTERY를 쓸 수 없다");
        }
        this.venue = venue;
        this.section = section;
        this.rowNo = rowNo;
        this.seatNo = seatNo;
        this.grade = grade;
    }
}
