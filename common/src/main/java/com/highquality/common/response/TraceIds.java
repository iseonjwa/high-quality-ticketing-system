package com.highquality.common.response;

import org.slf4j.MDC;

/**
 * 현재 요청의 추적 ID. Micrometer Tracing이 MDC에 넣는 traceId를 읽는다.
 * 추적을 붙이기 전이거나 MDC를 쓰지 않는 리액티브 스레드에서는 null이다.
 */
public final class TraceIds {

    private static final String MDC_KEY = "traceId";

    private TraceIds() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
