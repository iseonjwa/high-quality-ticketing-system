package com.iseonjwa.payment.billing.application;

import com.iseonjwa.payment.billing.domain.BillingKey;

// 응답에는 빌링키 원문·암호문을 담지 않는다. 화면 표시용 카드 끝 4자리만 준다
public record BillingKeyView(Long billingKeyId, String cardLast4, String status) {
    static BillingKeyView of(BillingKey billingKey) {
        return new BillingKeyView(billingKey.getId(), billingKey.getCardLast4(), billingKey.getStatus().name());
    }
}
