package com.iseonjwa.payment.payment.domain;

import java.util.List;

public interface PaymentStatusHistoryRepository {
    PaymentStatusHistory save(PaymentStatusHistory history);

    List<PaymentStatusHistory> findByPaymentId(Long paymentId);
}
