package com.iseonjwa.payment.payment.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// "먼저 조회하고 나중에 비교" 같은 우회는 HTTP 결과만으로는 가려지지 않아 어떤 조회 메서드를 쓰는지 직접 확인한다(0021 결정 1).
class PaymentQueryServiceTest {
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentQueryService service = new PaymentQueryService(payments);

    @Test
    void 일반_사용자는_소유자_조건_조회만_쓴다() {
        when(payments.findByIdAndUserId(7L, 1L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.find(7L, 1L, "USER"));

        verify(payments, never()).findById(anyLong());
    }

    @Test
    void ADMIN은_id로만_조회하고_소유자_조건을_쓰지_않는다() {
        when(payments.findById(7L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.find(7L, 1L, "ADMIN"));

        verify(payments, never()).findByIdAndUserId(anyLong(), anyLong());
        verify(payments, never()).existsById(anyLong());
    }

    @Test
    void 타인_확인이_실패해도_404를_유지한다() {
        when(payments.findByIdAndUserId(7L, 1L)).thenReturn(Optional.empty());
        when(payments.existsById(7L)).thenThrow(new DataAccessResourceFailureException("down"));

        var exception = assertThrows(BusinessException.class, () -> service.find(7L, 1L, "USER"));

        assertEquals(404, exception.getErrorCode().status());
    }

    @Test
    void 조회_중_DB_오류는_503이다() {
        when(payments.findByIdAndUserId(7L, 1L)).thenThrow(new DataAccessResourceFailureException("down"));

        var exception = assertThrows(BusinessException.class, () -> service.find(7L, 1L, "USER"));

        assertEquals(503, exception.getErrorCode().status());
    }
}
