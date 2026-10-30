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

@Entity
@Table(name = "venue_seats")
public class VenueSeat extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(nullable = false, length = 10)
    private String section;

    @Column(name = "row_no", nullable = false, length = 5)
    private String rowNo;

    @Column(name = "seat_no", nullable = false)
    private int seatNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SeatGrade grade;

    protected VenueSeat() {}

    public VenueSeat(Venue venue, String section, String rowNo, int seatNo, SeatGrade grade) {
        this.venue = venue;
        this.section = section;
        this.rowNo = rowNo;
        this.seatNo = seatNo;
        this.grade = grade;
    }

    public Long getId() { return id; }
    public Venue getVenue() { return venue; }
    public String getSection() { return section; }
    public String getRowNo() { return rowNo; }
    public int getSeatNo() { return seatNo; }
    public SeatGrade getGrade() { return grade; }
}
