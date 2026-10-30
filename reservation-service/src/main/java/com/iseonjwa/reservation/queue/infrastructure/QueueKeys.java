package com.iseonjwa.reservation.queue.infrastructure;

import java.util.List;

public record QueueKeys(String prefix) {

    static QueueKeys of(long scheduleId) {
        return new QueueKeys("queue:{" + scheduleId + "}:");
    }

    List<String> enterKeys() {
        return List.of(prefix + "seq", prefix + "waiting", prefix + "admitted", prefix + "issued");
    }
}
