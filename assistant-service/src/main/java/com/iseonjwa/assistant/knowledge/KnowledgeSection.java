package com.iseonjwa.assistant.knowledge;

import java.util.List;

/**
 * 규정 문서의 한 절. 모델에는 {@code id}를 문서 제목으로 넘기고, 화면에는 {@link #displayTitle()}을 보인다.
 */
public record KnowledgeSection(String id, String category, String title, List<String> keywords, String body) {
    public KnowledgeSection {
        keywords = List.copyOf(keywords);
    }

    public String displayTitle() {
        return category + " > " + title;
    }
}
