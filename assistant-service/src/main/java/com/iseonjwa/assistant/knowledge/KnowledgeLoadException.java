package com.iseonjwa.assistant.knowledge;

/** 규정 문서가 형식에 맞지 않으면 기동을 멈추려고 던진다. */
public class KnowledgeLoadException extends RuntimeException {
    public KnowledgeLoadException(String message) {
        super(message);
    }

    public KnowledgeLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
