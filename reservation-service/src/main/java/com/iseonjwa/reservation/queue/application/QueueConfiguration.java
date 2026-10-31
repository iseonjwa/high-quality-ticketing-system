package com.iseonjwa.reservation.queue.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(QueueProperties.class)
public class QueueConfiguration {
}
