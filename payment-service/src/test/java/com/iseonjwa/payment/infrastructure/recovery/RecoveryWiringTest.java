package com.iseonjwa.payment.infrastructure.recovery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 애플리케이션 컨텍스트(컴포넌트 스캔)에서 복구 작업 둘이 등록되고 스케줄러가 시작되는지 본다. 테스트 프로필은 두 작업을
 * 꺼 두므로 여기서만 켠다. 간격을 길게 줘서 이 테스트가 만든 스케줄러가 다른 테스트의 데이터를 집지 않게 한다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "payment.recheck.enabled=true", "payment.recheck.interval=1h",
        "payment.refund-worker.enabled=true", "payment.refund-worker.interval=1h"})
@ActiveProfiles("test")
// Note: 이 설정의 컨텍스트는 다른 테스트가 재사용하지 못한다. 캐시에 남으면 커넥션 풀이 테스트 DB의 max_connections(100)를 채운다
@DirtiesContext
class RecoveryWiringTest {
    @Autowired
    ApplicationContext context;

    @Test
    void 재확인과_환불_워커가_한_스케줄러에_등록되어_시작된다() {
        var names = context.getBeansOfType(RecoveryJob.class).values().stream().map(RecoveryJob::name).sorted().toList();

        assertEquals(List.of("recheck", "refund-worker"), names);
        assertEquals(1, context.getBeansOfType(RecoveryScheduler.class).size());
        assertTrue(context.getBean(RecoveryScheduler.class).isRunning());
    }
}
