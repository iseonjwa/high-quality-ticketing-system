package com.iseonjwa.mockpg.fault;

import java.util.Objects;

/**
 * 호출 종류 하나의 장애 설정. 불변이고 {@link FaultInjector}가 통째로 갈아 끼운다. 필드 이름이 설정 API의 JSON 필드다.
 * 규칙이 호출 종류별로 따로여서, 걸 수 없는 조합은 영원히 적용되지 않으므로 설정할 때 바로 거절한다.
 *
 * @param delayMs {@link FaultMode#DELAY}의 지연 시간(1~60,000). DELAY가 아니면 null이어야 한다
 * @param rate    요청마다 적용할 확률 0.0~1.0. 생략하면 1.0이다
 * @param count   남은 적용 횟수. null이면 해제할 때까지 계속 적용한다(부하 테스트용). 다 쓰면 NONE으로 돌아간다
 * @param orderId 승인·조회·망취소에서, 있으면 그 주문의 요청에만 적용하고 소비한다. null이면 모든 요청에 적용한다
 * @param refundId 환불·환불 조회에서 {@code orderId}와 같은 역할을 한다. 호출 종류에 맞지 않는 쪽을 주면 거절한다
 */
public record FaultRule(FaultTarget target, FaultMode mode, Long delayMs, Double rate, Integer count, String orderId,
        String refundId) {
    static final long MAX_DELAY_MILLIS = 60_000;

    public FaultRule {
        Objects.requireNonNull(target, "target이 필요합니다");
        Objects.requireNonNull(mode, "mode가 필요합니다");
        if (!mode.appliesTo(target)) {
            throw new IllegalArgumentException("mode " + mode + "는 target " + target + "에 걸 수 없습니다");
        }
        if (mode == FaultMode.DELAY) {
            if (delayMs == null || delayMs < 1 || delayMs > MAX_DELAY_MILLIS) {
                throw new IllegalArgumentException("DELAY의 delayMs는 1 이상 " + MAX_DELAY_MILLIS + " 이하여야 합니다");
            }
        } else if (delayMs != null) {
            throw new IllegalArgumentException("delayMs는 DELAY에만 쓸 수 있습니다");
        }
        if (rate == null) {
            rate = 1.0;
        } else if (rate.isNaN() || rate < 0.0 || rate > 1.0) {
            throw new IllegalArgumentException("rate는 0.0 이상 1.0 이하여야 합니다");
        }
        if (count != null && count < 1) {
            throw new IllegalArgumentException("count는 1 이상이어야 합니다");
        }
        if (orderId != null && orderId.isBlank()) {
            throw new IllegalArgumentException("orderId는 비어 있으면 안 됩니다");
        }
        if (refundId != null && refundId.isBlank()) {
            throw new IllegalArgumentException("refundId는 비어 있으면 안 됩니다");
        }
        // 호출 종류에 맞지 않는 한정자는 받아 두면 영원히 적용되지 않거나(잘못된 쪽을 건 경우) 전역 규칙으로 오해된다
        if (target.isRefund() && orderId != null) {
            throw new IllegalArgumentException("target " + target + "에는 orderId 대신 refundId를 써야 합니다");
        }
        if (!target.isRefund() && refundId != null) {
            throw new IllegalArgumentException("target " + target + "에는 refundId 대신 orderId를 써야 합니다");
        }
    }

    static FaultRule none(FaultTarget target) {
        return new FaultRule(target, FaultMode.NONE, null, null, null, null, null);
    }

    boolean isActive() {
        return mode != FaultMode.NONE;
    }

    /** 한정자가 없으면 모든 요청에, 있으면 그 주문·환불의 요청에만 일치한다. */
    boolean matches(String requestSubjectId) {
        var scope = target.isRefund() ? refundId : orderId;
        return scope == null || scope.equals(requestSubjectId);
    }

    FaultRule afterOneUse() {
        if (count == null) return this;
        return count == 1 ? none(target) : new FaultRule(target, mode, delayMs, rate, count - 1, orderId, refundId);
    }
}
