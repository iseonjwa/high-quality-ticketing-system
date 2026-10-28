package com.iseonjwa.payment.payment.application;

import com.iseonjwa.common.identity.Roles;
import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * 결제 조회(api.md 2절, 0021). 일반 사용자는 소유자 조건이 쿼리에 들어간 조회만 쓰고, ADMIN만 id로 조회한다.
 * 타인의 결제와 없는 결제는 같은 404라서 호출자는 둘을 구분할 수 없다. 이 클래스는 트랜잭션을 열지 않는다.
 *
 * <p>Note: 타인 결제 접근을 WARN으로 남기려면 둘을 구분해야 하므로, 일반 사용자의 소유자 조회가 비었을 때만
 * {@code existsById}를 한 번 더 부른다. 이 확인은 로그 전용이라 결과로 응답을 나누지 않고, 확인이 실패해도 삼켜서
 * 404를 유지한다(응답이 DB 상태로 갈리지 않게 한다).
 */
@Service
public class PaymentQueryService {
    private static final Logger log = LoggerFactory.getLogger(PaymentQueryService.class);

    private final PaymentRepository payments;

    public PaymentQueryService(PaymentRepository payments) {
        this.payments = payments;
    }

    public PaymentDetail find(Long paymentId, Long userId, String role) {
        try {
            return Roles.ADMIN.equals(role) ? findAsAdmin(paymentId) : findAsOwner(paymentId, userId);
        } catch (DataAccessException | TransactionException exception) {
            // 승인 API와 같은 의미로 응답한다. 클라이언트는 202 뒤에 이 API를 다시 부른다
            log.warn("결제 조회 중 DB 오류", exception);
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private PaymentDetail findAsAdmin(Long paymentId) {
        return payments.findById(paymentId).map(PaymentDetail::of).orElseThrow(PaymentQueryService::notFound);
    }

    private PaymentDetail findAsOwner(Long paymentId, Long userId) {
        var found = payments.findByIdAndUserId(paymentId, userId);
        if (found.isEmpty()) {
            warnIfOthers(paymentId, userId);
            throw notFound();
        }
        return PaymentDetail.of(found.get());
    }

    private void warnIfOthers(Long paymentId, Long userId) {
        try {
            if (payments.existsById(paymentId)) {
                log.warn("타인의 결제 조회 시도 userId={} paymentId={} traceId={}", userId, paymentId, MDC.get("traceId"));
            }
        } catch (DataAccessException | TransactionException exception) {
            log.debug("타인 결제 확인 실패, 404로 응답한다", exception);
        }
    }

    private static BusinessException notFound() {
        return new BusinessException(CommonErrorCode.NOT_FOUND);
    }
}
