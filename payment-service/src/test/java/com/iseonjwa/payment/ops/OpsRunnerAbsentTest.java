package com.iseonjwa.payment.ops;

import com.iseonjwa.payment.ops.presentation.OpsCommandLineRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@ActiveProfiles("test")
class OpsRunnerAbsentTest {
    @Autowired ApplicationContext context;

    @Test
    void ops_프로필이_아니면_기동_때_명령을_실행하는_빈이_없다() {
        assertTrue(context.getBeansOfType(OpsCommandLineRunner.class).isEmpty());
    }
}
