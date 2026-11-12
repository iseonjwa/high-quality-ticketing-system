package com.iseonjwa.common.web;

import java.time.Duration;
import java.util.Objects;

// 요청 로그 한 줄을 남길지 정한다. Gateway(WebFlux)와 서블릿 필터가 같은 기준을 쓰도록 웹 프레임워크에 기대지 않는다.
// ALL은 모든 요청, PROBLEMS는 굿풋(200 응답이면서 200ms 안)에서 빠진 요청만 남긴다.
// Note: 배포 서버 7차 H2에서 요청 로그를 끄자 서버 CPU가 1,500/s 93% → 85%로 내려갔다. 비용 대부분은 JVM 밖 로그 경로
// (표준 출력 → Docker → Alloy → Loki)라 줄 수 자체를 줄여야 한다. 4xx는 오픈 순간 정상 업무 거절(409 등)이 많아 기본으로 남기지 않고 지표로 본다.
public record RequestLogPolicy(Mode mode, int minStatus, Duration slowThreshold) {
    public enum Mode { ALL, PROBLEMS }

    public RequestLogPolicy {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(slowThreshold, "slowThreshold");
    }

    // cancelled는 응답 전에 클라이언트가 끊은 요청이다. 상태 코드가 없으므로 따로 받는다.
    public boolean shouldLog(int status, boolean cancelled, long elapsedMillis) {
        if (mode == Mode.ALL) {
            return true;
        }
        return cancelled || status >= minStatus || elapsedMillis >= slowThreshold.toMillis();
    }
}
