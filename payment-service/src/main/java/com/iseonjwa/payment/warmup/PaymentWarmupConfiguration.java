package com.iseonjwa.payment.warmup;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.payment.observability.PaymentMetrics;
import com.iseonjwa.payment.payment.application.PaymentTokenVerifier;
import com.iseonjwa.payment.payment.domain.IdempotencyRecordRepository;
import com.iseonjwa.payment.payment.domain.PaymentRepository;
import com.iseonjwa.payment.payment.domain.PgProvider;
import com.iseonjwa.payment.pg.application.PgCheckoutProfile;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgOrderId;
import com.iseonjwa.payment.pg.infrastructure.UnmeteredMockPg;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.UUID;

/**
 * 기동 웜업의 단계를 만든다. 모두 읽기 전용이고 결제·멱등 기록·이력·Outbox 행을 만들지 않는다.
 * 쓰기 경로는 데우지 않는다(롤백해도 식별자 시퀀스를 쓰고, 도메인 쓰기를 가짜 데이터로 돌리게 된다).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WarmupProperties.class)
class PaymentWarmupConfiguration {
    // 형식만 맞는 가짜 토큰이다. 서명이 맞지 않아 거절되고, 그 전에 디코더·서명 검증 클래스가 올라온다
    private static final String FAKE_TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ3YXJtdXAifQ.c2lnbmF0dXJlLXdhcm11cC1ub3QtcmVhbA";

    @Bean
    @ConditionalOnProperty(prefix = "payment.warmup", name = "enabled", matchIfMissing = true)
    PaymentWarmup paymentWarmup(WarmupProperties properties, PaymentMetrics metrics, TransactionTemplate tx,
            IdempotencyRecordRepository idempotency, PaymentRepository payments, PaymentTokenVerifier tokens,
            PgCheckoutProfile pgProfile, UnmeteredMockPg mockPg) {
        var steps = new ArrayList<WarmupStep>();
        steps.add(dbStep(tx, idempotency, payments));
        steps.add(tokenStep(tokens));
        // 토스는 외부 호출 한도가 있어 부르지 않는다. 서버 기본 PG가 Mock일 때만 Mock 조회를 데운다
        if (pgProfile.provider() == PgProvider.MOCK) {
            steps.add(pgStep(mockPg.client()));
        }
        return new PaymentWarmup(steps, properties, metrics);
    }

    static WarmupStep dbStep(TransactionTemplate tx, IdempotencyRecordRepository idempotency, PaymentRepository payments) {
        return new WarmupStep("db", () -> tx.executeWithoutResult(status -> {
            // 승인 흐름의 조회 세 가지를 없는 값으로 부른다. 결과는 늘 비어 있다
            idempotency.find(0L, UUID.randomUUID());
            payments.findByReservationId(-1L);
            payments.findById(-1L);
        }));
    }

    static WarmupStep tokenStep(PaymentTokenVerifier tokens) {
        return new WarmupStep("token", () -> {
            try {
                tokens.verify(FAKE_TOKEN);
            } catch (BusinessException expected) {
                // 거절이 정상이다
            }
        });
    }

    // Note: 어댑터를 직접 부른다. Bulkhead·차단기(PgApprovalGate·PgRecoveryGate)와 PG 호출 지표를 거치지 않아 웜업이 실패해도 차단기 실패로 세지 않는다
    static WarmupStep pgStep(PgClient mockAdapter) {
        return new WarmupStep("pg", () -> {
            var result = mockAdapter.findPayment(new PgOrderId(UUID.randomUUID()));
            if (!(result instanceof PgClient.NotFound)) {
                throw new IllegalStateException("없는 주문 조회가 NotFound가 아니다: " + result.getClass().getSimpleName());
            }
        });
    }
}
