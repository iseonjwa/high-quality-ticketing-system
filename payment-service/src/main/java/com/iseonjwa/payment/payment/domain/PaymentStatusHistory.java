package com.iseonjwa.payment.payment.domain;

import com.iseonjwa.payment.refund.domain.RefundStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
    public static final String PAYMENT = "PAYMENT";
    public static final String REFUND = "REFUND";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    @Column(name = "target_type", nullable = false, updatable = false, length = 10)
    private String targetType;

    // Note: 결제와 환불 상태 이름을 한 컬럼에 함께 쓴다(target_type으로 구분). 환불에는 FAILED처럼 PaymentStatus에
    // 없는 값이 있어 열거형으로 묶지 않고 이름 문자열로 저장한다.
    @Column(name = "from_status", updatable = false, length = 20)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, updatable = false, length = 20)
    private String toStatus;

    @Column(updatable = false, length = 50)
    private String reason;

    @Column(nullable = false, updatable = false, length = 40)
    private String actor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentStatusHistory() {}

    private PaymentStatusHistory(Long paymentId, String targetType, String from, String to, String reason,
            StatusActor actor, Instant createdAt) {
        this.paymentId = paymentId;
        this.targetType = targetType;
        this.fromStatus = from;
        this.toStatus = to;
        this.reason = reason;
        this.actor = actor.value();
        this.createdAt = createdAt;
    }

    public static PaymentStatusHistory of(Long paymentId, PaymentStatus from, PaymentStatus to, String reason,
            StatusActor actor, Instant createdAt) {
        return new PaymentStatusHistory(paymentId, PAYMENT, from == null ? null : from.name(), to.name(), reason, actor,
                createdAt);
    }

    /** 환불 전이 한 건의 기록. 같은 결제의 이력으로 쓰고 {@code target_type}으로 결제 전이와 구분한다. */
    public static PaymentStatusHistory ofRefund(Long paymentId, RefundStatus from, RefundStatus to, String reason,
            StatusActor actor, Instant createdAt) {
        return new PaymentStatusHistory(paymentId, REFUND, from == null ? null : from.name(), to.name(), reason, actor,
                createdAt);
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public String getTargetType() { return targetType; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getReason() { return reason; }
    public String getActor() { return actor; }
    public Instant getCreatedAt() { return createdAt; }
}
