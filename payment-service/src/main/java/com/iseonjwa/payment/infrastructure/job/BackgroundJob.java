package com.iseonjwa.payment.infrastructure.job;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 배경 실행기({@link BackgroundJobScheduler})에서 도는 주기 작업 하나(events-and-reliability.md 6·7절).
 *
 * @param name       작업 이름. 로그와 지표의 {@code job} 라벨 값이다(등록된 고정 이름이라 카디널리티가 낮다)
 * @param interval   한 주기가 끝난 뒤 다음 주기까지의 간격
 * @param initialDelay 등록한 뒤 첫 주기까지의 지연. 0이면 기동 직후 바로 돈다
 * @param maxPerTick 한 주기에 처리하는 최대 건수. 이 만큼 처리하면 남은 건은 다음 주기로 넘긴다
 * @param next       한 건(정리는 한 배치)을 처리한다. 계속할 일이 있으면 true, 할 일이 없거나 PG 복구 허가·정리 잠금을 못 얻었으면 false다(그 주기를 끝낸다)
 */
public record BackgroundJob(String name, Duration interval, Duration initialDelay, int maxPerTick,
        BooleanSupplier next) {
    /** 첫 주기를 한 간격 뒤에 시작하는 작업. */
    public BackgroundJob(String name, Duration interval, int maxPerTick, BooleanSupplier next) {
        this(name, interval, interval, maxPerTick, next);
    }

    public BackgroundJob {
        Objects.requireNonNull(name);
        Objects.requireNonNull(next);
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("배경 작업의 첫 지연은 0 이상이어야 합니다 job=" + name);
        }
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("배경 작업 간격은 0보다 커야 합니다 job=" + name);
        }
        if (maxPerTick < 1) {
            throw new IllegalArgumentException("배경 작업의 주기당 최대 건수는 1 이상이어야 합니다 job=" + name);
        }
    }
}
