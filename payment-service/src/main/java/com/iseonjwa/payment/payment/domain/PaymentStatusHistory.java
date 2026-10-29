package com.iseonjwa.payment.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 결제 상태 전이 한 건의 기록. 기록용 테이블이라 BaseEntity를 상속하지 않고 한 번 쓰면 바꾸지 않는다.
 */
@Entity
@Table(name = "payment_status_histories")
public class PaymentStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    @Column(name = "target_type", nullable = false, updatable = false, length = 10)
    private String targetType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false, length = 20)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 20)
    private PaymentStatus toStatus;

    @Column(updatable = false, length = 50)
    private String reason;

    @Column(nullable = false, updatable = false, length = 40)
    private String actor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentStatusHistory() {}

    private PaymentStatusHistory(Long paymentId, PaymentStatus from, PaymentStatus to, String reason,
            StatusActor actor, Instant createdAt) {
        this.paymentId = paymentId;
        this.targetType = "PAYMENT";
        this.fromStatus = from;
        this.toStatus = to;
        this.reason = reason;
        this.actor = actor.value();
        this.createdAt = createdAt;
    }

    public static PaymentStatusHistory of(Long paymentId, PaymentStatus from, PaymentStatus to, String reason,
            StatusActor actor, Instant createdAt) {
        return new PaymentStatusHistory(paymentId, from, to, reason, actor, createdAt);
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public String getTargetType() { return targetType; }
    public PaymentStatus getFromStatus() { return fromStatus; }
    public PaymentStatus getToStatus() { return toStatus; }
    public String getReason() { return reason; }
    public String getActor() { return actor; }
    public Instant getCreatedAt() { return createdAt; }
}
