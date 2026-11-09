package com.iseonjwa.reservation.concert.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SeatMapProperties.class)
public class SeatMapConfiguration {
}
