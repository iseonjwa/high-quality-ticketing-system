package com.iseonjwa.payment.warmup;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertTrue;

// 웜업을 켠 채로 실제 기동한다. 웹 서버가 뜬 뒤 웜업이 끝나 있고(start 완료), 로그 순서는 콘솔에서
// "event=PAYMENT_WARMUP step=total"이 "Tomcat started on port"보다 먼저 나오는 것으로 확인한다(ISE-243).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
                "payment.warmup.enabled=true", "payment.warmup.iterations=3"})
@ActiveProfiles("test")
class PaymentWarmupStartupOrderTest {
    @Autowired
    PaymentWarmup warmup;

    @Test
    void 켜면_웜업_빈이_만들어지고_기동_중에_끝났다() {
        assertTrue(warmup.isRunning());
    }
}
