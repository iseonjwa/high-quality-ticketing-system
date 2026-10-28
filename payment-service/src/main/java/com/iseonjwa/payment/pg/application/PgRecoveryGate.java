package com.iseonjwa.payment.pg.application;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 조회·늦은 승인 방지·환불·환불 조회 호출의 보호({@code pg-recovery} 그룹, 0018). 이미 생긴 금전 의무를 해소하는
 * 호출이라 신규 승인과 자원을 나눠, 승인 장애가 복구를 막지 않고 복구 폭주가 승인 용량을 빼앗지 않게 한다.
 */
public interface PgRecoveryGate {
    /**
     * 허가를 얻어 호출하고 결과로 정산한다. 허가를 못 얻으면 호출하지 않고 빈 값을 돌려준다. 호출한 쪽은 다음 주기로
     * 넘기고 시도 횟수를 세지 않는다. 예외가 나면 실패로 세고 그대로 다시 던진다.
     *
     * @param isFailure 결과를 CB에 실패로 셀지 정한다(보통 {@link PgClient.Unknown})
     */
    <R> Optional<R> execute(Supplier<R> call, Predicate<R> isFailure);
}
