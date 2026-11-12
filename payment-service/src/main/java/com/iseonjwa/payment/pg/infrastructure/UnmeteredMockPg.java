package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.pg.application.PgClient;

/**
 * 지표로 감싸기 전의 Mock 어댑터. 기동 웜업이 호출 지표와 차단기를 거치지 않고 어댑터를 직접 부르려고 노출한다(ISE-243).
 * {@code PgClient} 빈이 아니라 값을 든 껍데기라 PG 클라이언트 주입에 끼어들지 않는다.
 */
public record UnmeteredMockPg(PgClient client) {}
