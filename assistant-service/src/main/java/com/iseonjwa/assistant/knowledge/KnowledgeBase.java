package com.iseonjwa.assistant.knowledge;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * 기동할 때 {@code knowledge/*.md}를 읽어 둔 규정 문서 묶음이다. 절ID가 겹치거나 형식이 틀리면 기동을 멈춘다.
 */
public final class KnowledgeBase {
    private static final String PATTERN = "classpath*:knowledge/*.md";

    private final List<KnowledgeSection> sections;

    public KnowledgeBase(List<KnowledgeSection> sections) {
        this.sections = List.copyOf(sections);
        var ids = new HashSet<String>();
        for (var section : this.sections) {
            if (!ids.add(section.id())) {
                throw new KnowledgeLoadException("절ID가 겹칩니다: " + section.id());
            }
        }
        if (this.sections.isEmpty()) {
            throw new KnowledgeLoadException("규정 문서가 비었습니다");
        }
    }

    public static KnowledgeBase loadFromClasspath() {
        try {
            var resources = Arrays.stream(new PathMatchingResourcePatternResolver().getResources(PATTERN))
                    .sorted(Comparator.comparing(resource -> String.valueOf(resource.getFilename())))
                    .toList();
            var sections = new ArrayList<KnowledgeSection>();
            for (Resource resource : resources) {
                var text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                sections.addAll(KnowledgeParser.parse(String.valueOf(resource.getFilename()), text));
            }
            return new KnowledgeBase(sections);
        } catch (IOException exception) {
            throw new KnowledgeLoadException("규정 문서를 읽지 못했습니다", exception);
        }
    }

    public List<KnowledgeSection> sections() {
        return sections;
    }
}
