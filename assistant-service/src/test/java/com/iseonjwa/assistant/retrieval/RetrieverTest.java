package com.iseonjwa.assistant.retrieval;

import com.iseonjwa.assistant.global.AssistantProperties;
import com.iseonjwa.assistant.knowledge.KnowledgeBase;
import com.iseonjwa.assistant.knowledge.KnowledgeSection;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetrieverTest {
    private final KnowledgeBase knowledge = KnowledgeBase.loadFromClasspath();
    private final Retriever retriever = new Retriever(knowledge,
            new AssistantProperties.Retrieval(4, 3, 2.0));

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            오픈 시각이 몇 시예요?                      | open-time.when
            한 번에 몇 장까지 살 수 있어요?              | purchase-limit.per-user
            매수를 넘겨서 예매가 거절됐어요              | purchase-limit.exceeded
            대기열에서 순번을 어떻게 알 수 있나요        | queue.waiting
            입장권이 만료됐어요                         | queue.entry-pass
            결제가 거절됐어요                           | payment.declined
            시간이 지난 뒤 결제가 승인되면 환불되나요     | payment.auto-refund
            예매를 취소하고 싶어요                      | cancel-refund.user-cancel
            다른 사람이 먼저 잡은 좌석을 골랐어요        | booking-flow.seat-taken
            추첨에 당첨되면 어떻게 결제돼요?             | lottery.auto-pay
            """)
    void 골든_질문은_기대한_절이_상위_두_개_안에_든다(String question, String expectedId) {
        var top = retriever.rank(question).sections(2);

        assertThat(top).extracting(KnowledgeSection::id).contains(expectedId);
    }

    @Test
    void 상위_절_수는_설정을_따른다() {
        assertThat(retriever.forSearch("환불은 언제 되나요?")).hasSize(3);
        assertThat(retriever.forModel("환불은 언제 되나요?")).hasSize(4);
    }

    @Test
    void 환불_질문은_환불_절을_가장_앞에_둔다() {
        var ids = retriever.forSearch("환불은 언제 되나요?").stream().map(KnowledgeSection::id).toList();

        assertThat(ids).first().isIn("payment.auto-refund", "cancel-refund.auto-refund");
        assertThat(ids).anyMatch(id -> id.startsWith("cancel-refund.") || id.startsWith("payment."));
    }

    @Test
    void 점수가_기준보다_낮으면_모델에_전체_절을_넘긴다() {
        var sections = retriever.forModel("오늘 날씨 어때요");

        assertThat(sections).hasSameSizeAs(knowledge.sections());
    }

    @Test
    void 점수가_같으면_문서에_적힌_순서를_지킨다() {
        var ranking = retriever.rank("zzzz");

        assertThat(ranking.sections(Integer.MAX_VALUE))
                .extracting(KnowledgeSection::id)
                .containsExactlyElementsOf(knowledge.sections().stream().map(KnowledgeSection::id).toList());
    }

    @Test
    void 한_글자_낱말과_기호는_묶음을_만들지_않는다() {
        assertThat(Retriever.bigrams("a ! 가 ?")).isEmpty();
        assertThat(Retriever.bigrams("환불은?")).containsExactlyInAnyOrder("환불", "불은");
    }
}
