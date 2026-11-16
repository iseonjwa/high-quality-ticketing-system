package com.iseonjwa.reservation.seathold.application;

import java.util.function.Supplier;

/**
 * 선점의 DB 단계를 감싸는 문. 컨트롤러가 동시 처리 상한(SeatHoldConcurrencyLimit)을 넘긴다.
 * 서비스는 커넥션을 쓰는 트랜잭션 단계만 이 문 안에서 하고, 커밋 뒤 정리·서명은 문 밖에서 한다.
 */
@FunctionalInterface
public interface SeatHoldGate {
    // 상한 없이 바로 실행한다
    SeatHoldGate OPEN = new SeatHoldGate() {
        @Override
        public <T> T run(Supplier<T> work) {
            return work.get();
        }
    };

    <T> T run(Supplier<T> work);
}
