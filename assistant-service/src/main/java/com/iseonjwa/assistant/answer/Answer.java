package com.iseonjwa.assistant.answer;

import java.util.List;

/** 상담 응답. JSON 모양은 프론트 계약(frontend/src/api/assistant.ts)과 같다. */
public record Answer(String answer, Mode mode, FallbackReason fallbackReason, List<Source> sources) {
    public enum Mode {
        AI, SEARCH
    }

    public enum FallbackReason {
        /** 키가 없거나 꺼 둠 */
        LLM_DISABLED,
        /** 시한 초과, 429, 5xx, refusal, 동시 호출·일일 상한 */
        LLM_UNAVAILABLE
    }

    public record Source(String id, String title, String excerpt) {
    }

    public Answer {
        sources = List.copyOf(sources);
    }
}
