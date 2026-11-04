package com.iseonjwa.reservation.seathold.presentation.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Note: common의 @EnableScheduling은 Outbox 릴레이를 켤 때만 붙는다(CommonOutboxConfiguration).
// 릴레이 설정과 상관없이 만료 스케줄러가 돌도록 여기서도 켠다. 여러 곳에 붙어도 스케줄링은 한 번만 등록된다
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "seathold.expiry", name = "enabled", havingValue = "true", matchIfMissing = true)
class SeatHoldSchedulingConfiguration {
}
