package com.iseonjwa.gateway.security;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;
import java.util.Optional;

// 입장 토큰을 요구하는 경로 목록(계약 ① docs/design/queue/contracts.md 1절). 목록에 없는 경로는 검사하지 않는다.
// 대기열 API(/queue, /queue/me, /queue/token)와 추첨 응모(/lotteries/**, D3)는 대기열을 거치지 않으므로 넣지 않는다.
// AccessPolicy와 같은 PathPattern으로 Gateway가 파싱한 경로를 판정한다(ADR 0006).
final class EntryTokenPolicy {
    private static final String SCHEDULE_ID = "scheduleId";

    private static final List<Target> TARGETS = List.of(
            new Target(HttpMethod.GET, pattern("/api/v1/schedules/{scheduleId}/seats")),
            new Target(HttpMethod.POST, pattern("/api/v1/schedules/{scheduleId}/reservations")));

    private EntryTokenPolicy() {}

    // 대상 경로면 경로의 회차 ID 문자열을 돌려준다. 숫자인지는 호출한 쪽이 확인한다
    static Optional<String> scheduleIdOf(HttpMethod method, PathContainer path) {
        for (var target : TARGETS) {
            if (target.method().equals(method)) {
                var match = target.pattern().matchAndExtract(path);
                if (match != null) {
                    return Optional.of(match.getUriVariables().get(SCHEDULE_ID));
                }
            }
        }
        return Optional.empty();
    }

    private static PathPattern pattern(String value) {
        return PathPatternParser.defaultInstance.parse(value);
    }

    private record Target(HttpMethod method, PathPattern pattern) {}
}
