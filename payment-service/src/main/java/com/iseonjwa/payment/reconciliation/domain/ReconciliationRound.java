package com.iseonjwa.payment.reconciliation.domain;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * 대사 한 회차의 문맥. 서비스가 회차 시작 때 하나 만들어 모든 규칙의 준비 단계에 넘긴다. 예매 스냅숏은 처음 요구될 때 한 번만
 * 가져와 RC-1~RC-6이 나눠 쓴다(규칙마다 예매 API를 다시 부르지 않는다). 가져오다 실패하면 실패도 이 회차 안에서 그대로
 * 돌려준다. 같은 장애로 규칙마다 시한(2초)을 기다리지 않게 하기 위해서다.
 *
 * <p>Note: 가져오기는 트랜잭션 밖에서 일어난다. 규칙의 판단·기록 트랜잭션은 준비가 끝난 뒤에 시작하므로 예매 서비스를 기다리는
 * 동안 DB 연결과 잠금을 쥐지 않는다.
 */
public final class ReconciliationRound {
    private final Instant now;
    private final ReconciliationPolicy policy;
    private final Supplier<ReservationView> reservationLoader;
    private ReservationView reservations;
    private RuntimeException failure;

    public ReconciliationRound(Instant now, ReconciliationPolicy policy, Supplier<ReservationView> reservationLoader) {
        this.now = now;
        this.policy = policy;
        this.reservationLoader = reservationLoader;
    }

    public Instant now() {
        return now;
    }

    public ReconciliationPolicy policy() {
        return policy;
    }

    /** 이번 회차에 예매 스냅숏을 실제로 가져왔으면 그것을, 아니면(요구하지 않았거나 실패) null을 돌려준다. 가져오지는 않는다. */
    public synchronized ReservationView loadedReservations() {
        return reservations;
    }

    /** 이 회차의 예매 스냅숏. 처음 부르면 가져오고, 이미 실패했으면 다시 부르지 않고 실패를 돌려준다. */
    public synchronized ReservationView reservations() {
        if (reservations != null) return reservations;
        if (failure != null) throw new IllegalStateException("이번 회차의 예매 조회가 이미 실패했다", failure);
        try {
            reservations = reservationLoader.get();
            return reservations;
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        }
    }
}
