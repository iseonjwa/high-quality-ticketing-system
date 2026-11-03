package com.iseonjwa.payment.ops.application;

import com.iseonjwa.payment.payment.domain.StatusActor;

import java.time.Instant;

/**
 * 적용 트랜잭션 안에서 명령이 받는 실행 정보.
 *
 * @param operator 실행자
 * @param approver 승인자. 4-eyes 명령이 아니면 null일 수 있다
 * @param now      실행 시각. 이력·Outbox에 쓴다
 */
public record OpsApplyContext(String operator, String approver, Instant now) {
    /** 상태 이력의 행위자({@code OPS:<operator>}) */
    public StatusActor actor() {
        return StatusActor.ops(operator);
    }
}
