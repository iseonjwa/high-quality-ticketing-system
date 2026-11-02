package com.iseonjwa.payment.infrastructure.recovery;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 복구 실행기에서 도는 주기 작업 하나(events-and-reliability.md 6·7절).
 *
 * @param name       작업 이름. 로그와 지표(ISE-41)의 {@code job} 값이다
 * @param interval   한 주기가 끝난 뒤 다음 주기까지의 간격
 * @param maxPerTick 한 주기에 처리하는 최대 건수. 이 만큼 처리하면 남은 건은 다음 주기로 넘긴다
 * @param next       한 건을 처리한다. 처리했으면 true, 할 일이 없거나 PG 복구 허가를 못 얻었으면 false다(그 주기를 끝낸다)
 */
public record RecoveryJob(String name, Duration interval, int maxPerTick, BooleanSupplier next) {
    public RecoveryJob {
        Objects.requireNonNull(name);
        Objects.requireNonNull(next);
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("복구 작업 간격은 0보다 커야 합니다 job=" + name);
        }
        if (maxPerTick < 1) {
            throw new IllegalArgumentException("복구 작업의 주기당 최대 건수는 1 이상이어야 합니다 job=" + name);
        }
    }
}
