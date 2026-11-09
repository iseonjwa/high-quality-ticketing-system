package com.iseonjwa.assistant.knowledge;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeBaseTest {
    @Test
    void 규정_문서_묶음을_읽고_절ID가_유일하다() {
        var knowledge = KnowledgeBase.loadFromClasspath();

        var ids = knowledge.sections().stream().map(KnowledgeSection::id).toList();
        assertThat(ids).doesNotHaveDuplicates().hasSizeGreaterThanOrEqualTo(7);
        assertThat(new HashSet<>(knowledge.sections().stream().map(KnowledgeSection::category).toList()))
                .containsExactlyInAnyOrder("예매 흐름", "오픈 시각", "매수 제한", "대기열", "결제", "취소·환불", "추첨 예매");
        for (var section : knowledge.sections()) {
            assertThat(section.body()).as(section.id()).isNotBlank();
            assertThat(section.keywords()).as(section.id()).isNotEmpty();
        }
    }

    @Test
    void 규정_문서에_설정으로_바뀌는_숫자를_쓰지_않는다() {
        // Note: 선점 시간·매수 같은 숫자는 설정으로 바뀐다. 문서에 박으면 화면과 어긋난다(설계 3절)
        for (var section : KnowledgeBase.loadFromClasspath().sections()) {
            assertThat(section.body()).as(section.id()).doesNotContainPattern("\\d+\\s*(분|초|매|장)");
        }
    }

    @Test
    void 절ID가_겹치면_기동을_멈춘다() {
        var first = new KnowledgeSection("a.one", "분류", "하나", List.of("키"), "본문이에요.");
        var second = new KnowledgeSection("a.one", "분류", "둘", List.of("키"), "본문이에요.");

        assertThatThrownBy(() -> new KnowledgeBase(List.of(first, second)))
                .isInstanceOf(KnowledgeLoadException.class).hasMessageContaining("a.one");
    }

    @Test
    void 본문이_빈_절은_기동을_멈춘다() {
        var text = "# 분류\n\n## [a.empty] 빈 절\nkeywords: 키\n";

        assertThatThrownBy(() -> KnowledgeParser.parse("a.md", text))
                .isInstanceOf(KnowledgeLoadException.class).hasMessageContaining("a.empty");
    }

    @Test
    void keywords_줄이_없는_절은_기동을_멈춘다() {
        var text = "# 분류\n\n## [a.nokey] 키 없음\n본문이에요.\n";

        assertThatThrownBy(() -> KnowledgeParser.parse("a.md", text))
                .isInstanceOf(KnowledgeLoadException.class).hasMessageContaining("a.nokey");
    }

    @Test
    void 카테고리_줄이_없으면_기동을_멈춘다() {
        var text = "## [a.one] 제목\nkeywords: 키\n본문이에요.\n";

        assertThatThrownBy(() -> KnowledgeParser.parse("a.md", text))
                .isInstanceOf(KnowledgeLoadException.class).hasMessageContaining("카테고리");
    }

    @Test
    void 절이_하나도_없으면_기동을_멈춘다() {
        assertThatThrownBy(() -> KnowledgeParser.parse("a.md", "# 분류\n"))
                .isInstanceOf(KnowledgeLoadException.class);
    }

    @Test
    void 줄바꿈된_본문은_한_줄로_잇는다() {
        var sections = KnowledgeParser.parse("a.md", "# 분류\n\n## [a.one] 제목\nkeywords: 가나 다라\n첫째예요.\n둘째예요.\n");

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.body()).isEqualTo("첫째예요. 둘째예요.");
            assertThat(section.keywords()).containsExactly("가나", "다라");
            assertThat(section.displayTitle()).isEqualTo("분류 > 제목");
        });
    }
}
