package com.iseonjwa.payment.billing.domain;

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

/**
 * 사용자의 빌링키(SA 12-4). 원문 대신 암호문만 가진다. 사용자당 ACTIVE는 하나다({@code uq_billing_keys_user_active}).
 */
@Entity
@Table(name = "billing_keys")
public class BillingKey extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "billing_key_enc", nullable = false, updatable = false)
    private String billingKeyEnc;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_last4", length = 4, updatable = false)
    private String cardLast4;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BillingKeyStatus status;

    protected BillingKey() {}

    private BillingKey(Long userId, String billingKeyEnc, String cardLast4) {
        this.userId = userId;
        this.billingKeyEnc = billingKeyEnc;
        this.cardLast4 = cardLast4;
        this.status = BillingKeyStatus.ACTIVE;
    }

    public static BillingKey activate(Long userId, String billingKeyEnc, String cardLast4) {
        return new BillingKey(userId, billingKeyEnc, cardLast4);
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getBillingKeyEnc() { return billingKeyEnc; }
    public String getCardLast4() { return cardLast4; }
    public BillingKeyStatus getStatus() { return status; }
}
