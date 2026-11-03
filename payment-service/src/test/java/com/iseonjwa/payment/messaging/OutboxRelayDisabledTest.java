package com.iseonjwa.payment.messaging;

import com.iseonjwa.common.messaging.outbox.OutboxRelay;
import com.iseonjwa.common.messaging.outbox.OutboxRelayScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 테스트 프로필은 messaging.outbox.relay-enabled=false다. 이때는 릴레이 실행기를 만들지 않는다.
@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class OutboxRelayDisabledTest {
    @Autowired
    ApplicationContext context;

    @Test
    void relay_enabled가_false면_릴레이_실행기를_만들지_않는다() {
        assertTrue(context.getBeansOfType(OutboxRelayScheduler.class).isEmpty());
        assertEquals(1, context.getBeansOfType(OutboxRelay.class).size(), "수동으로 부르는 릴레이는 남는다");
    }
}
