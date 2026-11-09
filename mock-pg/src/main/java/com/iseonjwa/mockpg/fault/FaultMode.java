package com.iseonjwa.mockpg.fault;

import java.util.EnumSet;
import java.util.Set;

/**
 * 주입할 장애의 종류와 걸 수 있는 호출(mock-pg.md 5절의 모드와 적용 표). 운영형과, PG가 잘못 응답하는 변조형이 있다.
 * 변조형은 {@code test} 프로필에서만 설정할 수 있다({@link #tampering()}, FaultController).
 */
public enum FaultMode {
    NONE(EnumSet.allOf(FaultTarget.class)),
    // 응답 시간 초과를 만든다. 승인은 지연 동안 IN_FLIGHT로 기다린다
    DELAY(EnumSet.of(FaultTarget.APPROVE, FaultTarget.PAYMENT_LOOKUP, FaultTarget.CANCEL, FaultTarget.REFUND,
            FaultTarget.REFUND_LOOKUP)),
    // 새 주문을 CARD_DECLINED로 저장한다
    DECLINE(EnumSet.of(FaultTarget.APPROVE)),
    // 원장 처리를 끝낸 뒤 응답을 한 바이트도 보내지 않고 연결을 닫는다.
    // Note: POST에만 건다. JDK HttpClient는 응답을 한 바이트도 받지 못한 채 끊긴 GET을 자동으로 한 번 다시 보내서
    // 조회에 걸면 재시도가 성공해 유실을 만들 수 없다(5절)
    DROP_RESPONSE(EnumSet.of(FaultTarget.APPROVE, FaultTarget.CANCEL, FaultTarget.REFUND)),
    // 원장을 건드리지 않고 202 PROCESSING
    PROCESSING(EnumSet.of(FaultTarget.APPROVE, FaultTarget.PAYMENT_LOOKUP, FaultTarget.CANCEL, FaultTarget.REFUND,
            FaultTarget.REFUND_LOOKUP)),
    // 원장을 건드리지 않고 500
    SERVER_ERROR(EnumSet.of(FaultTarget.APPROVE, FaultTarget.PAYMENT_LOOKUP, FaultTarget.CANCEL, FaultTarget.REFUND,
            FaultTarget.REFUND_LOOKUP)),
    // 환불을 저장하지 않고 422 NOT_REFUNDABLE
    REJECT(EnumSet.of(FaultTarget.REFUND)),
    // 변조형: 어댑터가 PG의 잘못된 응답을 어떻게 해석하는지 계약 테스트가 확인하려고 둔다
    // 원장을 건드리지 않고 400 INVALID_REQUEST
    BAD_REQUEST(EnumSet.of(FaultTarget.APPROVE), true),
    // 저장은 정상으로 하고 응답에서 amount·currency만 뺀다
    MISSING_AMOUNT(EnumSet.of(FaultTarget.APPROVE, FaultTarget.PAYMENT_LOOKUP, FaultTarget.REFUND,
            FaultTarget.REFUND_LOOKUP), true),
    // 요청 금액에서 1,000원을 뺀 금액으로 저장하고 승인한다
    WRONG_AMOUNT(EnumSet.of(FaultTarget.APPROVE), true),
    // 요청 금액에서 0.5원을 뺀 금액으로 저장하고 승인한다
    FRACTIONAL_AMOUNT(EnumSet.of(FaultTarget.APPROVE), true);

    private final Set<FaultTarget> targets;
    private final boolean tampering;

    FaultMode(Set<FaultTarget> targets) {
        this(targets, false);
    }

    FaultMode(Set<FaultTarget> targets, boolean tampering) {
        this.targets = targets;
        this.tampering = tampering;
    }

    /** PG가 잘못 응답하는 변조형이면 true다. test 프로필에서만 설정할 수 있다. */
    public boolean tampering() {
        return tampering;
    }

    public boolean appliesTo(FaultTarget target) {
        return targets.contains(target);
    }
}
