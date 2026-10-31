package com.iseonjwa.reservation.queue.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "queue")
public record QueueProperties(int admitPerSecond,
                              Duration entryTokenTtl,
                              Duration pollMin,
                              Duration pollMax,
                              double pollJitterRatio) {
}
