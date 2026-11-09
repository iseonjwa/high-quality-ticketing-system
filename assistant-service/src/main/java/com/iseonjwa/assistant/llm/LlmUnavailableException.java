package com.iseonjwa.assistant.llm;

/** LLM에서 답을 받지 못했다. 호출한 쪽은 오류로 내지 않고 검색 모드로 강등한다. */
public class LlmUnavailableException extends RuntimeException {
    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
