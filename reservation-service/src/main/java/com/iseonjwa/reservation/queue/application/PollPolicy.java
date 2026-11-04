package com.iseonjwa.reservation.queue.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PollPolicy {

    private final QueueProperties queueProperties;


    public long estimatedWaitSeconds(long position) {
        return Math.ceilDiv(position, queueProperties.admitPerSecond());
    }

    public long nextPollAfterMillis(long estimatedWaitSeconds) {
        long base = Math.clamp(estimatedWaitSeconds * 1000, queueProperties.pollMin().toMillis(), queueProperties.pollMax().toMillis());
        long jitter = (long) (Math.random() * base * queueProperties.pollJitterRatio());
        return base + jitter;
    }
}
