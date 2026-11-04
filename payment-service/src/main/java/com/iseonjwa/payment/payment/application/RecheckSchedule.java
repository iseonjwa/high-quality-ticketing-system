package com.iseonjwa.payment.payment.application;

import java.time.Duration;
import java.util.Optional;

/**
 * 결제 재확인 간격(SA 9-4, events-and-reliability.md 6절). 10초×6회 → 1분 → 5분 → 15분이고, 실제로 PG를 부른 9번째 호출도
 * 확정하지 못하면 소진이다(약 22분).
 *
 * <p>환불 워커도 같은 간격을 쓴다(events-and-reliability.md 6절). 환불은 첫 호출이 바로 나가므로 첫 10초 대기가 없고, 그래서
 * n번째가 불확실하면 n = 1~5에서 10초 뒤에 다시 부른다.
 *
 * <p>Note: 첫 10초는 승인 API의 TX1이 이미 {@code next_check_at}에 넣었다. 그래서 시도 횟수 n은 "방금 n번째로 PG를 불렀다"는
 * 뜻이고, n번째가 불확실하면 n = 1~5에서 10초 뒤(합쳐 10초 6회), 6은 1분 뒤, 7은 5분 뒤, 8은 15분 뒤에 다시 부른다.
 */
public final class RecheckSchedule {
    public static final int MAX_ATTEMPTS = 9;

    private RecheckSchedule() {
    }

    /**
     * n번째 호출이 불확실했을 때 다음 호출까지의 간격. 소진이면 비어 있다.
     */
    public static Optional<Duration> delayAfter(int attempts) {
        if (attempts < 1) throw new IllegalArgumentException("시도 횟수는 1 이상이어야 한다 attempts=" + attempts);
        if (attempts >= MAX_ATTEMPTS) return Optional.empty();
        if (attempts <= 5) return Optional.of(Duration.ofSeconds(10));
        return switch (attempts) {
            case 6 -> Optional.of(Duration.ofMinutes(1));
            case 7 -> Optional.of(Duration.ofMinutes(5));
            default -> Optional.of(Duration.ofMinutes(15));
        };
    }
}
