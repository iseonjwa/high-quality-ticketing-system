package com.iseonjwa.payment.refund.domain;

import com.iseonjwa.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 결제당 하나인 전액 환불(data-model.md 4절).
 *
 * <p>Note: 환불 행은 {@link RefundRepository#createForApprovedPayment}의 {@code INSERT ... SELECT}로만 만든다.
 * 엔티티로 저장하면 결제 상태 조건과 금액 복사를 건너뛰게 되므로 생성자를 열지 않는다. 상태 컬럼은
 * {@code updatable = false}라 이후 전이도 저장소의 조건부 UPDATE로만 한다(ISE-137).
 */
@Entity
@Table(name = "refunds")
public class Refund extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    @Column(name = "pg_refund_id", nullable = false, updatable = false)
    private UUID pgRefundId;

    @Column(nullable = false, updatable = false, precision = 15, scale = 0)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private RefundReason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private RefundSource source;

    @Column(name = "source_event_id", updatable = false)
    private UUID sourceEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private RefundStatus status;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(nullable = false, updatable = false)
    private int attempt;

    @Column(name = "next_check_at", updatable = false)
    private Instant nextCheckAt;

    @Column(name = "pg_requested_at", updatable = false)
    private Instant pgRequestedAt;

    @Column(name = "pg_refund_ref", updatable = false, length = 200)
    private String pgRefundRef;

    @Column(name = "refunded_at", updatable = false)
    private Instant refundedAt;

    protected Refund() {}

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public UUID getPgRefundId() { return pgRefundId; }
    public BigDecimal getAmount() { return amount; }
    public RefundReason getReason() { return reason; }
    public RefundSource getSource() { return source; }
    public UUID getSourceEventId() { return sourceEventId; }
    public RefundStatus getStatus() { return status; }
    public int getAttempt() { return attempt; }
    public Instant getNextCheckAt() { return nextCheckAt; }
    public Instant getPgRequestedAt() { return pgRequestedAt; }
    public String getPgRefundRef() { return pgRefundRef; }
    public Instant getRefundedAt() { return refundedAt; }
}
