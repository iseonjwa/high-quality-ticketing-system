package com.iseonjwa.reservation.lottery.presentation.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Note: 추첨 스케줄러와 같은 조건으로 켜서 lottery.scheduler.enabled=false면 추첨 쪽에서 스케줄링을 켜지 않는다.
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "lottery.scheduler.enabled", havingValue = "true", matchIfMissing = true)
class LotterySchedulingConfiguration {
}
