package com.iseonjwa.reservation.concert.domain.entity;

import com.iseonjwa.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.SQLRestriction;

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(name = "concerts")
public class Concert extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    // 운영자 두 명이 같은 공연을 동시에 고치면 늦게 저장한 쪽이 실패한다(낙관적 락).
    @Version
    @Column(nullable = false)
    private Long version;

    protected Concert() {}

    public Concert(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
}
