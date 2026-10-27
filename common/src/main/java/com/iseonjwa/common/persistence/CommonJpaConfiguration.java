package com.iseonjwa.common.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Import(CommonAuditingEntityListener.class)
public class CommonJpaConfiguration {
    @Bean
    Clock commonAuditingClock() {
        return Clock.systemUTC();
    }
}
