package com.iseonjwa.payment.pg.application;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 조회·늦은 승인 방지·환불·환불 조회 호출의 보호({@code pg-recovery} 그룹, 0018). 이미 생긴 금전 의무를 해소하는
 * 호출이라 신규 승인과 자원을 나눠, 승인 장애가 복구를 막지 않고 복구 폭주가 승인 용량을 빼앗지 않게 한다.
 */
public interface PgRecoveryGate {
    /**
     * 허가만 얻는다. 못 얻으면(Circuit Breaker OPEN·Bulkhead 초과) 비어 있고 어떤 자원도 점유하지 않는다. 얻은 쪽이
     * {@link PgPermit#callStarted}, {@link PgPermit#settle}, {@link PgPermit#close}를 맡는다. 재확인 스케줄러가 "허가 먼저,
     * claim 나중"을 지키려고 쓴다(events-and-reliability.md 6절).
     */
    Optional<PgPermit> tryAcquire();

    /**
     * 허가를 얻어 호출하고 결과로 정산한다({@link PgPermit#settle}). 허가를 못 얻으면 호출하지 않고 빈 값을 돌려준다.
     * 호출한 쪽은 다음 주기로 넘기고 시도 횟수를 세지 않는다. {@code call}은 null을 돌려주지 않아야 하며, 예외(null 포함)가
     * 나면 실패로 세고 그대로 다시 던진다. Error는 결과로 세지 않고 허가만 돌려준다.
     */
    <R> Optional<R> execute(Supplier<R> call);
}
