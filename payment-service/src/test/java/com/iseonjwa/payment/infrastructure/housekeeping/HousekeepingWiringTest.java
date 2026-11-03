package com.iseonjwa.payment.infrastructure.housekeeping;

import com.iseonjwa.payment.infrastructure.job.BackgroundJobScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 애플리케이션 컨텍스트에서 정리 작업 셋이 housekeeping 실행기에 올라 시작되는지 본다. 테스트 프로필은 정리를 꺼 두므로
 * 여기서만 켠다. 간격을 길게 줘서 이 테스트가 만든 스케줄러가 다른 테스트의 데이터를 지우지 않게 한다.
 */
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "payment.housekeeping.enabled=true", "payment.housekeeping.interval=1h"})
@ActiveProfiles("test")
// Note: 이 설정의 컨텍스트는 다른 테스트가 재사용하지 못한다. 캐시에 남으면 커넥션 풀이 테스트 DB의 max_connections(100)를 채운다
@DirtiesContext
class HousekeepingWiringTest {
    @Autowired
    ApplicationContext context;

    @Test
    void 정리_작업_셋이_복구와_다른_실행기에서_시작된다() {
        var housekeeping = context.getBean("housekeepingScheduler", BackgroundJobScheduler.class);

        assertEquals(3, housekeeping.jobNames().size());
        assertTrue(housekeeping.isRunning());
        assertEquals(2, context.getBeansOfType(BackgroundJobScheduler.class).size());
    }
}
