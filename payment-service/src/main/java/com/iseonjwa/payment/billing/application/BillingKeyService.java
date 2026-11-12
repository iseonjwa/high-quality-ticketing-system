package com.iseonjwa.payment.billing.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.payment.billing.domain.BillingKey;
import com.iseonjwa.payment.billing.domain.BillingKeyRepository;
import com.iseonjwa.payment.payment.application.PaymentErrorCode;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 빌링키 등록·해지(SA 6-6 F5-4, ISE-96). PG 발급은 트랜잭션 밖에서 하고, 기존 활성 키 해지와 새 키 저장만 한 트랜잭션으로 묶는다.
 * 응모 때는 빌링키를 확인하지 않는다(추첨 D4). 없으면 자동결제가 {@code BILLING_KEY_MISSING}으로 거절된다.
 */
@Service
public class BillingKeyService {
    private static final Logger log = LoggerFactory.getLogger(BillingKeyService.class);
    // 같은 사용자가 동시에 등록해 활성 유니크에 부딪히면 한 번 더 해지·저장한다. 계속 겹치면 마지막 예외를 그대로 낸다
    private static final int MAX_ATTEMPTS = 2;

    private final PgClients pgClients;
    private final BillingKeyRepository billingKeys;
    private final BillingKeyEncryptor encryptor;
    private final TransactionTemplate tx;
    private final BillingKeyRegistrationLimiter limiter;

    public BillingKeyService(PgClients pgClients, BillingKeyRepository billingKeys, BillingKeyEncryptor encryptor,
            TransactionTemplate tx, BillingKeyRegistrationLimiter limiter) {
        this.pgClients = pgClients;
        this.billingKeys = billingKeys;
        this.encryptor = encryptor;
        this.tx = tx;
        this.limiter = limiter;
    }

    public BillingKeyView register(Long userId, String authKey) {
        // PG 호출 전에 사용자별 상한을 본다. 넘으면 PG를 부르지 않는다
        limiter.tryAcquire(userId).ifPresent(retryAfter -> {
            throw new BillingKeyRateLimitedException(retryAfter);
        });
        var issued = switch (pgClients.billing().issueBillingKey(authKey, customerKey(userId))) {
            case PgClient.BillingKeyIssued value -> value;
            case PgClient.BillingKeyRejected rejected -> throw new BusinessException(BillingErrorCode.BILLING_KEY_REJECTED);
            case PgClient.Unknown unknown -> {
                log.warn("빌링키 발급 결과를 알 수 없다 userId={} cause={}", userId, unknown.cause());
                throw new BusinessException(PaymentErrorCode.PG_UNAVAILABLE);
            }
        };
        var encrypted = encryptor.encrypt(issued.billingKey());
        for (var attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> {
                    billingKeys.revokeActive(userId);
                    return BillingKeyView.of(billingKeys.save(
                            BillingKey.activate(userId, encrypted, issued.cardLast4())));
                });
            } catch (DataIntegrityViolationException exception) {
                if (attempt >= MAX_ATTEMPTS) throw exception;
                log.info("빌링키 동시 등록 충돌, 다시 시도 userId={}", userId);
            }
        }
    }

    // 없어도 성공으로 본다(같은 요청을 다시 보내도 결과가 같다)
    public boolean revoke(Long userId) {
        var revoked = tx.execute(status -> billingKeys.revokeActive(userId));
        return revoked != null && revoked > 0;
    }

    // PG가 빌링키를 묶는 고객 식별자. 사용자 ID를 그대로 드러내지 않게 접두어를 붙인다
    private static String customerKey(Long userId) {
        return "iseonjwa-user-" + userId;
    }
}
