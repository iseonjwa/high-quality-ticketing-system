package com.iseonjwa.payment.warmup;

import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.observability.PaymentMetrics;
import com.iseonjwa.payment.payment.application.PaymentTokenVerifier;
import com.iseonjwa.payment.payment.domain.IdempotencyRecordRepository;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PgProvider;
import com.iseonjwa.payment.pg.application.PgCheckoutProfile;
import com.iseonjwa.payment.pg.infrastructure.UnmeteredMockPg;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentWarmupConfigurationTest {
    @Test
    void PG_단계는_NotFound가_아니면_실패로_본다() {
        var pg = mock(PgClient.class);
        when(pg.findPayment(any())).thenReturn(new PgClient.NotFound());
        assertDoesNotThrow(() -> PaymentWarmupConfiguration.pgStep(pg).action().run());

        when(pg.findPayment(any())).thenReturn(new PgClient.Unknown(
                com.iseonjwa.payment.pg.application.UnknownCause.CONNECTION_LOST));
        assertThrows(IllegalStateException.class, () -> PaymentWarmupConfiguration.pgStep(pg).action().run());
    }

    @Test
    void 서버_기본_PG가_토스면_PG_단계를_건너뛰고_Mock이면_회차만큼_부른다() {
        var pg = mock(PgClient.class);
        when(pg.findPayment(any())).thenReturn(new PgClient.NotFound());

        warmup(PgProvider.TOSS, pg).start();
        verify(pg, never()).findPayment(any());

        warmup(PgProvider.MOCK, pg).start();
        verify(pg, times(3)).findPayment(any());
    }

    private PaymentWarmup warmup(PgProvider provider, PgClient pg) {
        return new PaymentWarmupConfiguration().paymentWarmup(new WarmupProperties(true, 3, null),
                new PaymentMetrics(new SimpleMeterRegistry()), mock(TransactionTemplate.class),
                mock(IdempotencyRecordRepository.class), mock(PaymentRepository.class), mock(PaymentTokenVerifier.class),
                new PgCheckoutProfile(provider, null, false), new UnmeteredMockPg(pg));
    }
}
