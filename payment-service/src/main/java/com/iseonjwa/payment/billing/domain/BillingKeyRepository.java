package com.iseonjwa.payment.billing.domain;

import java.util.Optional;

public interface BillingKeyRepository {
    BillingKey save(BillingKey billingKey);

    Optional<BillingKey> findActiveByUserId(Long userId);

    // 사용자의 ACTIVE 빌링키를 REVOKED로 바꾸고 바꾼 행 수를 돌려준다
    int revokeActive(Long userId);
}
