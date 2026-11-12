package com.iseonjwa.reservation.concert.presentation;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// 배치 캐시를 Redis에 둔 경로도 로컬 캐시와 같은 응답을 내는지 같은 시험으로 본다(측정 브랜치 전용, STEPS.md "P4b 배치 캐시 로컬 대 Redis")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
                "seatmap.layout-cache=redis"})
@ActiveProfiles("test")
class ScheduleSeatRedisLayoutApiTest extends ScheduleSeatApiTest {
}
