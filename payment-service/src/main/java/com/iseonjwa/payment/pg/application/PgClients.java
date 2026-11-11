package com.iseonjwa.payment.pg.application;

import com.iseonjwa.payment.payment.domain.PgProvider;

/** 결제마다 그 결제를 처리한 PG로 호출을 보낸다(ADR 0023 2026-11-10 개정). */
public interface PgClients {
    /** 설정에 없는 PG(토스 키 없이 TOSS)면 PG를 부르지 않고 {@code Unknown(CLIENT_ERROR)}로 답하는 클라이언트를 돌려준다(예외 아님) */
    PgClient of(PgProvider provider);

    /** 빌링(추첨 자동결제)은 서버 모드와 상관없이 늘 Mock이다 */
    default PgClient billing() {
        return of(PgProvider.MOCK);
    }
}
