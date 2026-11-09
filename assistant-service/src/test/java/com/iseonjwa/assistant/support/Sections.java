package com.iseonjwa.assistant.support;

import com.iseonjwa.assistant.knowledge.KnowledgeBase;
import com.iseonjwa.assistant.knowledge.KnowledgeSection;

import java.util.List;

/** 테스트에서 쓰는 실제 규정 문서 절. */
public final class Sections {
    private static final KnowledgeBase KNOWLEDGE = KnowledgeBase.loadFromClasspath();

    private Sections() {
    }

    public static KnowledgeSection byId(String id) {
        return KNOWLEDGE.sections().stream().filter(section -> section.id().equals(id)).findFirst().orElseThrow();
    }

    public static List<KnowledgeSection> all() {
        return KNOWLEDGE.sections();
    }
}
