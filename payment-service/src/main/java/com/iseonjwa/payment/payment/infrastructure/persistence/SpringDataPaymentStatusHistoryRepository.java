package com.iseonjwa.payment.payment.infrastructure.persistence;

import com.iseonjwa.payment.payment.domain.PaymentStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface SpringDataPaymentStatusHistoryRepository extends JpaRepository<PaymentStatusHistory, Long> {
    List<PaymentStatusHistory> findByPaymentIdOrderByIdAsc(Long paymentId);
}
