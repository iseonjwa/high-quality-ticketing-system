package com.iseonjwa.payment.ops;

import com.iseonjwa.payment.infrastructure.job.BackgroundJobScheduler;
import com.iseonjwa.payment.ops.presentation.OpsCommandLineRunner;
import com.netflix.discovery.EurekaClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ops} 프로필로 기동한 컨텍스트에 웹 서버·Kafka 리스너·배경 실행기가 없는지 확인한다(ADR 0028 결정 6).
 *
 * <p>Note: {@code application.yml}의 {@code on-profile: ops} 문서는 설정 서버를 import하는 {@code !test} 문서와 함께
 * 켜지지 않아 이 컨텍스트 테스트는 {@code test} 프로필을 같이 켠다. 테스트 프로필이 이미 꺼 두는 값(릴레이·워커·정리)은
 * 여기서 ops 문서의 효과를 증명하지 못한다. 그 값이 ops 문서에 있고 설정 서버가 덮어쓰지 않는다는 것은
 * {@link OpsProfileConfigTest}가 파일로 확인한다. 이 테스트는 ops 문서만 켜는 값(웹 서버 없음)과 명령 실행기 빈의
 * 연결을 본다.
 */
// Note: 다른 컨텍스트와 공유하지 못하는 구성이라 연결 풀을 작게 하고 끝나면 닫는다. 테스트 DB의 연결 수 제한(100)을
// 컨텍스트마다 풀(기본 10)이 나눠 쓰는데, 하나가 더 남으면 뒤에 도는 다른 통합 테스트가 연결을 얻지 못한다
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=0"})
@ActiveProfiles({"test", "ops"})
@DirtiesContext
class OpsProfileContextTest {
    @Autowired ApplicationContext context;
    @Autowired KafkaListenerEndpointRegistry kafkaListeners;
    @Autowired OpsCommandLineRunner commandLineRunner;

    @Test
    void 웹_서버를_띄우지_않는다() {
        assertFalse(context instanceof WebApplicationContext, context.getClass().getName());
        assertEquals("none", context.getEnvironment().getProperty("spring.main.web-application-type"));
    }

    @Test
    void Kafka_리스너를_시작하지_않는다() {
        assertFalse(kafkaListeners.getListenerContainers().isEmpty(), "리스너 컨테이너가 등록되어 있어야 이 확인이 의미 있다");
        assertTrue(kafkaListeners.getListenerContainers().stream().noneMatch(container -> container.isRunning()));
    }

    @Test
    void 배경_작업_실행기에_올라간_작업이_없고_릴레이_실행기도_없다() {
        var schedulers = context.getBeansOfType(BackgroundJobScheduler.class);
        assertFalse(schedulers.isEmpty());
        schedulers.forEach((name, scheduler) -> {
            assertEquals(java.util.List.of(), scheduler.jobNames(), name);
            assertFalse(scheduler.isRunning(), name);
        });
        assertTrue(context.getBeansOfType(com.iseonjwa.common.messaging.outbox.OutboxRelayScheduler.class).isEmpty());
    }

    @Test
    void Eureka에_등록하지_않는다() {
        assertTrue(context.getBeansOfType(EurekaClient.class).isEmpty());
    }

    @Test
    void 기동하면_명령_실행기가_돌아_인자가_없으면_오류_코드를_남긴다() {
        assertEquals(1, commandLineRunner.getExitCode());
    }
}
