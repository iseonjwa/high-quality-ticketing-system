package com.iseonjwa.payment.reconciliation;

import com.iseonjwa.payment.reconciliation.application.ReservationLookup;
import com.iseonjwa.payment.reconciliation.domain.ReservationSnapshot;
import com.iseonjwa.payment.reconciliation.infrastructure.ReservationLookupHttpClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 예매 대사 규칙(RC-1~RC-6)을 켠 컨텍스트에서 예매 조회 포트를 가짜 서버로 향하게 한다. 운영 어댑터(HTTP 클라이언트)를 그대로
 * 쓰고 주소만 가짜 서버로 바꾼다. 읽기 시한은 시한 초과 시험이 오래 걸리지 않게 0.3초로 줄였다.
 *
 * <p>Note: 서버는 정적 하나를 컨텍스트가 공유한다. 컨텍스트는 클래스마다 캐시되므로 서버 주소가 컨텍스트마다 달라지면 안 된다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeReservationConfiguration {
    public static final FakeReservationServer SERVER = new FakeReservationServer();
    /** 포트가 불린 순간 트랜잭션이 걸려 있었는지(호출마다 하나). */
    public static final List<Boolean> TRANSACTION_ACTIVE_AT_CALL = new CopyOnWriteArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(SERVER::close));
    }

    @Bean
    @Primary
    ReservationLookup fakeServerReservationLookup() {
        var real = ReservationLookupHttpClient.create(SERVER.baseUrl(), Duration.ofSeconds(1), Duration.ofMillis(300), null);
        return new ReservationLookup() {
            @Override
            public List<ReservationSnapshot> findByIds(Collection<Long> ids) {
                TRANSACTION_ACTIVE_AT_CALL.add(TransactionSynchronizationManager.isActualTransactionActive());
                return real.findByIds(ids);
            }

            @Override
            public ChangesPage findChanges(Instant from, Instant to, String cursor) {
                TRANSACTION_ACTIVE_AT_CALL.add(TransactionSynchronizationManager.isActualTransactionActive());
                return real.findChanges(from, to, cursor);
            }
        };
    }
}
