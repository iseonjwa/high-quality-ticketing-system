package com.iseonjwa.mockpg.fault;

import java.util.EnumSet;
import java.util.Set;

/**
 * 주입할 장애의 종류와 걸 수 있는 호출(mock-pg.md 5절의 모드와 적용 표). 응답을 만지지 않는 운영형만 있다.
 * 변조형(BAD_REQUEST 등)은 ISE-130에서 더한다.
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
    REJECT(EnumSet.of(FaultTarget.REFUND));

    private final Set<FaultTarget> targets;

    FaultMode(Set<FaultTarget> targets) {
        this.targets = targets;
    }

    public boolean appliesTo(FaultTarget target) {
        return targets.contains(target);
    }
}
