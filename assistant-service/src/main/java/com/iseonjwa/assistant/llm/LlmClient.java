package com.iseonjwa.assistant.llm;

import com.iseonjwa.assistant.knowledge.KnowledgeSection;

import java.util.List;

/** 고른 절을 근거로 답을 만드는 LLM. 키가 없거나 꺼 두면 이 빈이 없고 검색 모드로만 답한다. */
public interface LlmClient {
    /**
     * @throws LlmUnavailableException 시한 초과, 429, 5xx, 연결 실패, refusal처럼 답을 받지 못했을 때
     */
    LlmAnswer answer(String question, List<KnowledgeSection> sections);

    /** 답 본문과, 답이 인용한 절(중복 없이 처음 인용한 순서). 인용이 없으면 빈 목록이다. */
    record LlmAnswer(String text, List<KnowledgeSection> citedSections) {
        public LlmAnswer {
            citedSections = List.copyOf(citedSections);
        }
    }
}
