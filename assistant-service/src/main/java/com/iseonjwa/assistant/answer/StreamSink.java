package com.iseonjwa.assistant.answer;

import com.iseonjwa.assistant.answer.Answer.FallbackReason;
import com.iseonjwa.assistant.answer.Answer.Mode;

import java.util.List;

/**
 * 스트리밍 답변 이벤트를 받는 쪽. 이벤트 이름·필드는 docs/design/assistant/README.md 2-1절 계약이다.
 * 클라이언트가 끊겨 더 보낼 수 없으면 {@link ClientDisconnectedException}을 던진다.
 */
public interface StreamSink {
    void meta(Mode mode, FallbackReason fallbackReason);

    void delta(String text);

    void fallback(FallbackReason fallbackReason);

    void sources(List<Answer.Source> sources);

    void done(String answer, Mode mode, FallbackReason fallbackReason);

    /** 클라이언트가 연결을 끊어 이벤트를 보낼 수 없다. 서비스는 LLM 스트림을 닫고 조용히 끝낸다. */
    class ClientDisconnectedException extends RuntimeException {
        public ClientDisconnectedException(Throwable cause) {
            super("클라이언트 연결이 끊겼습니다", cause);
        }
    }
}
