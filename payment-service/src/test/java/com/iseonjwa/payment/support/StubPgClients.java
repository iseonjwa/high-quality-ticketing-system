package com.iseonjwa.payment.support;

import com.iseonjwa.payment.payment.domain.PgProvider;
import com.iseonjwa.payment.pg.application.PgClient;
import com.iseonjwa.payment.pg.application.PgClients;

import java.util.EnumMap;
import java.util.Map;

/**
 * 테스트용 {@link PgClients}. 기본은 모든 PG를 한 클라이언트로 보내고, 테스트가 {@link #route}로 PG마다 다른 클라이언트를
 * 지정하면 그 PG만 따로 보낸다(결제의 PG별 라우팅을 확인할 때). 컨텍스트가 테스트 사이에 재사용되므로 끝나면 {@link #reset}한다.
 */
public final class StubPgClients implements PgClients {
    private final PgClient defaultClient;
    private final Map<PgProvider, PgClient> routes = new EnumMap<>(PgProvider.class);

    public StubPgClients(PgClient defaultClient) {
        this.defaultClient = defaultClient;
    }

    public void route(PgProvider provider, PgClient client) {
        routes.put(provider, client);
    }

    public void reset() {
        routes.clear();
    }

    @Override
    public PgClient of(PgProvider provider) {
        return routes.getOrDefault(provider, defaultClient);
    }
}
