package com.iseonjwa.assistant.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 규정 문서 한 파일을 절 목록으로 바꾼다.
 *
 * <pre>
 * # 카테고리
 * ## [절ID] 제목
 * keywords: 낱말 낱말 ...
 * 본문
 * </pre>
 */
final class KnowledgeParser {
    private static final Pattern CATEGORY = Pattern.compile("^#\\s+(.+)$");
    private static final Pattern HEADING = Pattern.compile("^##\\s+\\[([^\\]\\s]+)]\\s+(.+)$");
    private static final String KEYWORDS_PREFIX = "keywords:";

    private KnowledgeParser() {
    }

    static List<KnowledgeSection> parse(String source, String text) {
        var sections = new ArrayList<KnowledgeSection>();
        String category = null;
        String id = null;
        String title = null;
        List<String> keywords = List.of();
        var body = new StringBuilder();

        for (var rawLine : text.split("\\R")) {
            var line = rawLine.strip();
            var categoryMatch = CATEGORY.matcher(line);
            var headingMatch = HEADING.matcher(line);
            if (headingMatch.matches()) {
                finish(sections, source, category, id, title, keywords, body);
                id = headingMatch.group(1);
                title = headingMatch.group(2).strip();
                keywords = List.of();
                body.setLength(0);
            } else if (categoryMatch.matches()) {
                category = categoryMatch.group(1).strip();
            } else if (line.startsWith(KEYWORDS_PREFIX)) {
                keywords = List.of(line.substring(KEYWORDS_PREFIX.length()).strip().split("\\s+"));
            } else if (!line.isEmpty() && id != null) {
                if (!body.isEmpty()) {
                    body.append(' ');
                }
                body.append(line);
            }
        }
        finish(sections, source, category, id, title, keywords, body);
        if (sections.isEmpty()) {
            throw new KnowledgeLoadException(source + ": 절이 하나도 없습니다");
        }
        return sections;
    }

    private static void finish(List<KnowledgeSection> sections, String source, String category, String id,
            String title, List<String> keywords, StringBuilder body) {
        if (id == null) {
            return;
        }
        if (category == null || category.isBlank()) {
            throw new KnowledgeLoadException(source + ": 카테고리(# 제목) 줄이 절보다 앞에 있어야 합니다");
        }
        if (body.isEmpty()) {
            throw new KnowledgeLoadException(source + ": 본문이 빈 절이 있습니다: " + id);
        }
        if (keywords.isEmpty() || keywords.get(0).isBlank()) {
            throw new KnowledgeLoadException(source + ": keywords 줄이 없는 절이 있습니다: " + id);
        }
        sections.add(new KnowledgeSection(id, category, title, keywords, body.toString()));
    }
}
