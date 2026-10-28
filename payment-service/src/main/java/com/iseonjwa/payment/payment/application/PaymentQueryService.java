package com.iseonjwa.payment.payment.application;

import com.iseonjwa.common.identity.Roles;
import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 조회(api.md 2절, 0021). 일반 사용자는 소유자 조건이 쿼리에 들어간 조회만 쓰고, ADMIN만 id로 조회한다.
 * 타인의 결제와 없는 결제는 같은 404라서 호출자는 둘을 구분할 수 없다.
 *
 * <p>Note: 타인 결제 접근을 WARN으로 남기려면 둘을 구분해야 하므로, 소유자 조회가 비었을 때만 존재 여부를 한 번 더
 * 확인한다. 이 확인은 로그에만 쓰이고 응답에는 영향이 없다.
 */
@Service
public class PaymentQueryService {
    private static final Logger log = LoggerFactory.getLogger(PaymentQueryService.class);

    private final PaymentRepository payments;

    public PaymentQueryService(PaymentRepository payments) {
        this.payments = payments;
    }

    @Transactional(readOnly = true)
    public PaymentDetail find(Long paymentId, Long userId, String role) {
        var found = Roles.ADMIN.equals(role) ? payments.findById(paymentId) : payments.findByIdAndUserId(paymentId, userId);
        if (found.isEmpty()) {
            if (payments.existsById(paymentId)) {
                log.warn("타인의 결제 조회 시도 userId={} paymentId={} traceId={}", userId, paymentId, MDC.get("traceId"));
            }
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        return PaymentDetail.of(found.get());
    }
}
