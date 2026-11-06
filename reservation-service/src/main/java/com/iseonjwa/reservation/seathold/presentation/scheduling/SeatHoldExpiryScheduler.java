package com.iseonjwa.reservation.seathold.presentation.scheduling;

import com.iseonjwa.reservation.seathold.application.SeatHoldExpiryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;

/**
 * 선점 기한이 지난 예매를 주기적으로 만료한다(ISE-13). 한 주기에 최대 {@code batchSize}건을 한 건씩 처리한다.
 * 남은 건은 다음 주기에 이어서 처리한다.
 *
 * <p>Note: 건마다 트랜잭션을 나눈다. 한 건이 실패해도 앞서 만료한 건은 커밋되어 있고, 실패한 건은 HELD로 남아 다음 주기에 다시 대상이 된다.
 */
@Component
@ConditionalOnProperty(prefix = "seathold.expiry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SeatHoldExpiryScheduler {
    private static final Logger log = LoggerFactory.getLogger(SeatHoldExpiryScheduler.class);

    private final SeatHoldExpiryService expiry;
    private final int batchSize;

    public SeatHoldExpiryScheduler(SeatHoldExpiryService expiry,
            @Value("${seathold.expiry.batch-size:100}") int batchSize) {
        this.expiry = expiry;
        this.batchSize = batchSize;
    }

    // fixedDelay라 앞 주기가 끝난 뒤 간격을 센다. 한 인스턴스 안에서 주기가 겹치지 않는다
    @Scheduled(fixedDelayString = "${seathold.expiry.interval:5s}", initialDelayString = "${seathold.expiry.interval:5s}")
    public void expireDueHolds() {
        var failed = new HashSet<Long>();
        var expired = 0;
        var attempts = 0;
        // 한 건씩 집어 처리한다. 다른 인스턴스가 잡은 예매는 SKIP LOCKED로 건너뛰므로 같은 예매를 두 인스턴스가 처리하지 않는다
        while (attempts < batchSize) {
            attempts++;
            try {
                var attempt = expiry.expireNext(failed);
                if (attempt.isEmpty()) {
                    break;
                }
                if (attempt.get().expired()) {
                    expired++;
                }
            } catch (SeatHoldExpiryService.ExpiryFailedException exception) {
                // 실패한 예매는 이번 주기에서 다시 집지 않는다. 롤백되어 HELD로 남고 다음 주기에 다시 대상이 된다
                failed.add(exception.reservationId());
                log.error("선점 만료에 실패했습니다. 다음 주기에 다시 시도합니다. reservationId={}", exception.reservationId(),
                        exception.getCause());
            }
        }
        if (expired > 0) {
            log.info("선점 만료 {}건 처리 (시도 {}건, 실패 {}건)", expired, attempts, failed.size());
        }
    }
}
