package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.payment.domain.PgProvider;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgClients;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 설정으로 만든 PG 어댑터를 PG 종류별로 들고 있다. 설정에 없는 PG를 달라고 하면 PG를 부르지 않고
 * {@code Unknown(CLIENT_ERROR)}로 답하는 클라이언트를 돌려준다.
 *
 * <p>Note: 조용히 Mock으로 바꾸지 않는다. TOSS로 기록된 결제를 Mock에 물으면 존재하지 않는 주문으로
 * 보여 거절로 오인할 수 있다. 우리 쪽 설정 문제라 {@code CLIENT_ERROR}로 답해 복구 서킷 브레이커에 세지 않는다.
 * 예외나 {@code PG_ERROR}로 답하면 토스 건 몇 개만으로 CB가 열려 Mock 결제의 재확인·환불까지 멈춘다.
 */
public class ConfiguredPgClients implements PgClients {
    private final Map<PgProvider, PgClient> clients = new EnumMap<>(PgProvider.class);

    public ConfiguredPgClients(PgClient mock, PgClient tossOrNull) {
        clients.put(PgProvider.MOCK, Objects.requireNonNull(mock, "mock"));
        if (tossOrNull != null) clients.put(PgProvider.TOSS, tossOrNull);
    }

    @Override
    public PgClient of(PgProvider provider) {
        var client = clients.get(provider);
        return client != null ? client : new UnconfiguredPgClient(provider);
    }
}
