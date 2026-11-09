package com.iseonjwa.assistant.llm;

import com.iseonjwa.assistant.knowledge.KnowledgeSection;

import java.util.List;
import java.util.function.Consumer;

/** 고른 절을 근거로 답을 만드는 LLM. 키가 없거나 꺼 두면 이 빈이 없고 검색 모드로만 답한다. */
public interface LlmClient {
    /**
     * @throws LlmUnavailableException 시한 초과, 429, 5xx, 연결 실패, refusal처럼 답을 받지 못했을 때
     */
    LlmAnswer answer(String question, List<KnowledgeSection> sections);

    /**
     * 답을 만드는 대로 글자 조각을 {@code onDelta}에 넘기고, 다 받으면 최종 답과 인용 절을 돌려준다.
     * 구현이 스트리밍을 지원하지 않으면 답 전체를 조각 하나로 넘긴다.
     * {@code onDelta}가 던진 예외(클라이언트가 끊김 등)는 감싸지 않고 그대로 올라간다.
     *
     * @throws LlmUnavailableException 시한 초과, 연결 끊김, refusal처럼 답을 끝까지 받지 못했을 때
     */
    default LlmAnswer stream(String question, List<KnowledgeSection> sections, Consumer<String> onDelta) {
        var answer = answer(question, sections);
        onDelta.accept(answer.text());
        return answer;
    }

    /** 답 본문과, 답이 인용한 절(중복 없이 처음 인용한 순서). 인용이 없으면 빈 목록이다. */
    record LlmAnswer(String text, List<KnowledgeSection> citedSections) {
        public LlmAnswer {
            citedSections = List.copyOf(citedSections);
        }
    }
}
