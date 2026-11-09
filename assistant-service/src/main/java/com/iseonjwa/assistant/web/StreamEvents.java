package com.iseonjwa.assistant.web;

import com.iseonjwa.assistant.answer.Answer;

import java.util.List;

/** 스트리밍 답변 이벤트의 {@code data} 모양. 계약은 docs/design/assistant/README.md 2-1절이다. */
final class StreamEvents {
    private StreamEvents() {
    }

    record Meta(Answer.Mode mode, Answer.FallbackReason fallbackReason) {
    }

    record Delta(String text) {
    }

    record Fallback(Answer.FallbackReason fallbackReason) {
    }

    record Sources(List<Answer.Source> sources) {
    }

    record Done(String answer, Answer.Mode mode, Answer.FallbackReason fallbackReason) {
    }
}
