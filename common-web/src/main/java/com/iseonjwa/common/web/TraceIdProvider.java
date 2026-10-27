package com.iseonjwa.common.web;

import org.slf4j.MDC;

/**
 * 현재 요청의 추적 ID를 꺼낸다. Micrometer Tracing이 요청마다 MDC에 {@code traceId}를 넣어 둔다.
 * 추적이 꺼져 있으면 null이다.
 */
public class TraceIdProvider {

    private static final String TRACE_ID_KEY = "traceId";

    public String currentTraceId() {
        return MDC.get(TRACE_ID_KEY);
    }
}
