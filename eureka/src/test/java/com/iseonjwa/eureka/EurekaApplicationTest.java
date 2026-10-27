package com.iseonjwa.eureka;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false",
        "management.tracing.export.zipkin.enabled=false"
})
class EurekaApplicationTest {

    @Test
    void Config_서버_없이_test_프로필로_뜬다() {
    }
}
