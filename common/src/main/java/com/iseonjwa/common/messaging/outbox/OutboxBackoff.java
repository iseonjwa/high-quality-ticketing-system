package com.iseonjwa.common.messaging.outbox;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * 일시 오류 뒤 다음 시도까지의 지연(events-and-reliability.md 3절). k번째 실패 뒤 기본 지연은
 * {@code min(500ms × 2^(k-1), 60초)}이고, 실제 지연은 그 50~100%다(equal jitter).
 *
 * <p>Note: 완전 무작위(full jitter)는 0에 가까운 지연이 나와 장애 중인 브로커를 곧바로 다시 두드린다. 기본 지연의
 * 절반을 보장하고 나머지 절반만 흩뜨려, 여러 행이 같은 시각에 몰리는 것을 풀면서 최소 간격은 지킨다.
 * 횟수로 포기하지 않으므로 k가 아무리 커도 넘치지 않게 지수를 제한한다.
 */
public class OutboxBackoff {
    static final Duration INITIAL = Duration.ofMillis(500);
    static final Duration MAX = Duration.ofSeconds(60);
    // 500ms × 2^7 = 64초로 이미 상한을 넘으므로 그 이상의 지수는 계산하지 않는다
    private static final int MAX_EXPONENT = 7;

    private final DoubleSupplier random;

    /** @param random 0 이상 1 미만 난수원. 테스트에서 고정한다 */
    public OutboxBackoff(DoubleSupplier random) {
        this.random = random;
    }

    public static OutboxBackoff withRandomJitter() {
        return new OutboxBackoff(() -> ThreadLocalRandom.current().nextDouble());
    }

    /** k번째 실패(k ≥ 1) 뒤의 기본 지연. Jitter를 적용하기 전 값이다. */
    public static Duration baseDelay(int failures) {
        var exponent = Math.min(Math.max(failures - 1, 0), MAX_EXPONENT);
        var delay = INITIAL.multipliedBy(1L << exponent);
        return delay.compareTo(MAX) > 0 ? MAX : delay;
    }

    /** k번째 실패(k ≥ 1) 뒤 다음 시도까지의 지연. 기본 지연의 50~100%다. */
    public Duration delay(int failures) {
        var halfMillis = baseDelay(failures).toMillis() / 2;
        var jitterMillis = (long) (random.getAsDouble() * (halfMillis + 1));
        return Duration.ofMillis(halfMillis + Math.min(jitterMillis, halfMillis));
    }
}
