package com.iseonjwa.payment.scenario;

import com.iseonjwa.payment.pg.infrastructure.MockPgProcess;

/**
 * 시나리오 테스트가 함께 쓰는 실제 Mock PG 프로세스 하나. JVM에서 한 번만 띄우고 시나리오마다 {@code reset}으로 비운다.
 *
 * <p>Note: 스프링 컨텍스트를 시나리오 클래스끼리 재사용하려면 컨텍스트가 가리키는 Mock PG 주소가 클래스마다 같아야 한다.
 * 그래서 클래스마다 띄우지 않고 프로세스를 JVM 수명으로 둔다. 닫지 않아도 {@link MockPgProcess}의 종료 훅이 자식 JVM을 정리한다.
 */
final class ScenarioMockPg {
    private static MockPgProcess instance;

    private ScenarioMockPg() {}

    static synchronized MockPgProcess get() {
        if (instance == null) instance = MockPgProcess.start();
        return instance;
    }
}
