package com.iseonjwa.payment.billing.infrastructure.persistence;

import com.iseonjwa.payment.billing.domain.BillingKey;
import com.iseonjwa.payment.billing.domain.BillingKeyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface SpringDataBillingKeyRepository extends JpaRepository<BillingKey, Long> {
    Optional<BillingKey> findByUserIdAndStatus(Long userId, BillingKeyStatus status);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE billing_keys SET status = 'REVOKED', updated_at = now()
            WHERE user_id = :userId AND status = 'ACTIVE'
            """)
    int revokeActive(@Param("userId") Long userId);
}
