package com.iseonjwa.reservation.concert.domain.entity;

import com.iseonjwa.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

@Entity
@SQLRestriction("deleted_at IS NULL")
@Table(name = "venues")
public class Venue extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    protected Venue() {}

    public Venue(String name) {
        this.name = name;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
}
