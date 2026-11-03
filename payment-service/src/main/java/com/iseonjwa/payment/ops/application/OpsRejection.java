package com.iseonjwa.payment.ops.application;

/**
 * 명령이 계획을 만들거나 적용하지 않기로 했다(대상 없음, PG 근거 불일치, 조건부 UPDATE 0행 등). 종료 코드 2이고
 * {@code --apply} 시도였으면 REJECTED로 기록한다. 적용 트랜잭션 안에서 던지면 그 트랜잭션은 롤백된다.
 */
public class OpsRejection extends RuntimeException {
    public OpsRejection(String message) {
        super(message);
    }
}
