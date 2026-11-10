package com.iseonjwa.payment.pg.infrastructure;

import com.iseonjwa.payment.payment.domain.PgProvider;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgClients;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 설정으로 만든 PG 어댑터를 PG 종류별로 들고 있다. 설정에 없는 PG를 달라고 하면 예외로 알린다.
 *
 * <p>Note: 이 예외를 조용히 Mock으로 바꾸지 않는다. TOSS로 기록된 결제를 Mock에 물으면 존재하지 않는 주문으로
 * 보여 거절로 오인할 수 있다. 호출한 쪽이 PG 예외와 같이 결과 모름으로 다루게 둔다.
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
        if (client == null) {
            throw new IllegalStateException(provider + " PG가 설정되어 있지 않습니다(토스 시크릿 키 없음)");
        }
        return client;
    }
}
