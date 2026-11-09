package com.iseonjwa.assistant.web;

import com.iseonjwa.assistant.answer.Answer;
import com.iseonjwa.assistant.answer.Answer.FallbackReason;
import com.iseonjwa.assistant.answer.Answer.Mode;
import com.iseonjwa.assistant.answer.StreamSink;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 스트리밍 이벤트를 {@code event: 이름} / {@code data: JSON 한 줄}로 {@link SseEmitter}에 쓴다. */
final class SseStreamSink implements StreamSink {
    private final SseEmitter emitter;
    private final JsonMapper json;
    private final AtomicBoolean closed = new AtomicBoolean();

    SseStreamSink(SseEmitter emitter, JsonMapper json) {
        this.emitter = emitter;
        this.json = json;
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
    }

    @Override
    public void meta(Mode mode, FallbackReason fallbackReason) {
        send("meta", new StreamEvents.Meta(mode, fallbackReason));
    }

    @Override
    public void delta(String text) {
        send("delta", new StreamEvents.Delta(text));
    }

    @Override
    public void fallback(FallbackReason fallbackReason) {
        send("fallback", new StreamEvents.Fallback(fallbackReason));
    }

    @Override
    public void sources(List<Answer.Source> sources) {
        send("sources", new StreamEvents.Sources(sources));
    }

    @Override
    public void done(String answer, Mode mode, FallbackReason fallbackReason) {
        send("done", new StreamEvents.Done(answer, mode, fallbackReason));
    }

    // Note: data는 JSON을 문자열로 만들어 넘긴다. 줄바꿈은 JSON 이스케이프(\n)로만 나가 data가 항상 한 줄이다
    private void send(String name, Object payload) {
        if (closed.get()) {
            throw new ClientDisconnectedException(null);
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(json.writeValueAsString(payload)));
        } catch (IOException | IllegalStateException exception) {
            closed.set(true);
            throw new ClientDisconnectedException(exception);
        }
    }
}
