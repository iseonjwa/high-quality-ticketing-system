package com.iseonjwa.reservation.queue.infrastructure;

import java.util.List;

// Lua에 전달할 Key들 생성
public record QueueKeys(String prefix) {

    static QueueKeys of(long scheduleId) {
        return new QueueKeys("queue:{" + scheduleId + "}:");
    }

    String issuedKey() {
        return prefix + "issued";
    }

    List<String> enterKeys() {
        return List.of(prefix + "seq", prefix + "waiting", prefix + "admitted", prefix + "issued", prefix + "admitLog");
    }

    List<String> positionKeys() {
        return List.of(prefix + "waiting", prefix + "admitted", prefix + "issued", prefix + "admitLog");
    }

    List<String> admitKeys() {
        return List.of(prefix + "seq", prefix + "waiting", prefix + "admitted", prefix + "issued", prefix + "lastTick", prefix + "admitLog");
    }

    List<String> issueTokenKeys() {
        return List.of(prefix + "waiting", prefix + "admitted", prefix + "issued", prefix + "admitLog");
    }

    List<String> discardEntryTokenKeys() {
        return List.of(issuedKey());
    }

    List<String> statsKeys() {
        return List.of(prefix + "seq", prefix + "waiting", prefix + "admitted", prefix + "issued");
    }
}
