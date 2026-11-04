package com.iseonjwa.payment.payment.domain;

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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 예매당 하나인 논리적 결제(ADR 0026).
 *
 * <p>Note: 상태와 PG 결과 컬럼은 {@code updatable = false}다. 엔티티 변경 감지로 상태를 바꾸지 못하게 하고,
 * 전이는 {@link PaymentRepository}의 조건부 UPDATE로만 한다(domain-and-states.md 4절).
 */
@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {
    // 첫 PG 호출이 끝나지 않았을 때 재확인 스케줄러가 처음 집어 가는 시점
    static final Duration FIRST_RECHECK_DELAY = Duration.ofSeconds(10);
    private static final String KRW = "KRW";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_no", nullable = false, updatable = false, length = 20)
    private String paymentNo;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    private Long reservationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false, precision = 15, scale = 0)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private PaymentStatus status;

    // PG 호출 전에 저장해 PG 멱등키로 쓴다
    @Column(name = "pg_order_id", nullable = false, updatable = false)
    private UUID pgOrderId;

    @Column(name = "pg_tx_id", updatable = false, length = 200)
    private String pgTxId;

    @Column(name = "pg_approved_amount", updatable = false, precision = 15, scale = 0)
    private BigDecimal pgApprovedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "decline_code", updatable = false, length = 30)
    private DeclineReason declineCode;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "unknown_retry_count", nullable = false, updatable = false)
    private int unknownRetryCount;

    @Column(name = "next_check_at", updatable = false)
    private Instant nextCheckAt;

    @Column(name = "approved_at", updatable = false)
    private Instant approvedAt;

    @Column(name = "refunded_at", updatable = false)
    private Instant refundedAt;

    protected Payment() {}

    private Payment(String paymentNo, Long reservationId, Long userId, BigDecimal amount, PaymentMethod method,
            UUID pgOrderId, Instant now) {
        this.paymentNo = paymentNo;
        this.reservationId = reservationId;
        this.userId = userId;
        this.amount = amount;
        this.currency = KRW;
        this.method = method;
        this.status = PaymentStatus.REQUESTED;
        this.pgOrderId = pgOrderId;
        this.nextCheckAt = now.plus(FIRST_RECHECK_DELAY);
    }

    // 승인 API의 TX1이 만드는 첫 상태다. 금액은 검증을 마친 토큰 금액이다.
    public static Payment request(String paymentNo, Long reservationId, Long userId, BigDecimal amount,
            PaymentMethod method, UUID pgOrderId, Instant now) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("결제 금액은 0보다 커야 합니다");
        }
        return new Payment(paymentNo, reservationId, userId, amount, method, pgOrderId, now);
    }

    public Long getId() { return id; }
    public String getPaymentNo() { return paymentNo; }
    public Long getReservationId() { return reservationId; }
    public Long getUserId() { return userId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentMethod getMethod() { return method; }
    public PaymentStatus getStatus() { return status; }
    public UUID getPgOrderId() { return pgOrderId; }
    public String getPgTxId() { return pgTxId; }
    public BigDecimal getPgApprovedAmount() { return pgApprovedAmount; }
    public DeclineReason getDeclineCode() { return declineCode; }
    public int getUnknownRetryCount() { return unknownRetryCount; }
    public Instant getNextCheckAt() { return nextCheckAt; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getRefundedAt() { return refundedAt; }
}
