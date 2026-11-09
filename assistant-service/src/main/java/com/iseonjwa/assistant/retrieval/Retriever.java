package com.iseonjwa.assistant.retrieval;

import com.iseonjwa.assistant.global.AssistantProperties;
import com.iseonjwa.assistant.knowledge.KnowledgeBase;
import com.iseonjwa.assistant.knowledge.KnowledgeSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 질문과 절을 한글 두 글자 묶음(bigram)으로 나눠 겹치는 정도로 순위를 매긴다.
 * 여러 절에 흔한 묶음("나요" 같은 어미)이 점수를 지배하지 않도록 드문 묶음일수록 더 크게 센다.
 */
public final class Retriever {
    // Note: 질문 말투에서 나오는 묶음("어떻게 되나요", "있나요")은 어느 절에도 뜻이 없는데, 제목·keywords에 우연히 들어 있으면
    // 점수를 지배한다. 색인과 질문 양쪽에서 뺀다. 절을 쓸 때 이런 낱말은 keywords에 넣지 않는 것이 좋다
    private static final Set<String> QUESTION_FILLER = Set.of("나요", "어요", "에요", "예요", "해요", "가요", "까요", "세요",
            "어떻", "떻게", "되나", "있나", "하나", "언제", "어디", "얼마", "무엇", "무슨", "인가", "인지", "할까", "건가",
            "알려", "려줘", "주세", "해주", "싶어", "싶은", "수있", "있어", "없어", "않아", "안돼");

    // 제목·keywords에서 맞은 묶음은 본문에서만 맞은 묶음보다 두 배로 센다
    private static final double HEADLINE_WEIGHT = 2.0;
    // 본문에서 같은 묶음이 여러 번 나오면(최대 다섯 번까지) 그 절이 주제에 가깝다고 보고 조금 더 센다
    private static final double REPEAT_WEIGHT = 0.25;
    private static final int REPEAT_LIMIT = 5;

    private final List<IndexedSection> index;
    private final Map<String, Double> inverseFrequency;
    private final AssistantProperties.Retrieval settings;

    public Retriever(KnowledgeBase knowledge, AssistantProperties.Retrieval settings) {
        this.settings = settings;
        var indexed = new ArrayList<IndexedSection>();
        var documentFrequency = new HashMap<String, Integer>();
        for (var section : knowledge.sections()) {
            var headline = bigrams(section.title() + " " + String.join(" ", section.keywords()));
            var all = new HashSet<>(headline);
            var bodyCounts = countBigrams(section.body());
            all.addAll(bodyCounts.keySet());
            all.forEach(bigram -> documentFrequency.merge(bigram, 1, Integer::sum));
            indexed.add(new IndexedSection(section, headline, all, bodyCounts));
        }
        this.index = List.copyOf(indexed);
        var total = knowledge.sections().size();
        var weights = new HashMap<String, Double>();
        documentFrequency.forEach((bigram, count) -> weights.put(bigram, Math.log(1.0 + (double) total / count)));
        this.inverseFrequency = Map.copyOf(weights);
    }

    /** 점수가 높은 순서로 정렬한 모든 절. 점수가 같으면 문서에 적힌 순서를 지킨다. */
    public Ranking rank(String question) {
        var asked = bigrams(question);
        var scored = new ArrayList<ScoredSection>();
        for (var candidate : index) {
            scored.add(new ScoredSection(candidate.section(), score(asked, candidate)));
        }
        scored.sort(Comparator.comparingDouble(ScoredSection::score).reversed());
        return new Ranking(scored);
    }

    /** 모델에 넘길 절. 최고 점수가 기준보다 낮으면 질문과 닮은 절이 없다고 보고 전체를 넘긴다. */
    public List<KnowledgeSection> forModel(String question) {
        var ranking = rank(question);
        if (ranking.bestScore() < settings.minScore()) {
            return ranking.sections(Integer.MAX_VALUE);
        }
        return ranking.sections(settings.topK());
    }

    /** 검색 모드로 보여 줄 절. */
    public List<KnowledgeSection> forSearch(String question) {
        return rank(question).sections(settings.searchResultCount());
    }

    private double score(Set<String> asked, IndexedSection candidate) {
        var score = 0.0;
        for (var bigram : asked) {
            if (!candidate.all().contains(bigram)) {
                continue;
            }
            var weight = inverseFrequency.getOrDefault(bigram, 0.0);
            score += candidate.headline().contains(bigram) ? weight * HEADLINE_WEIGHT : weight;
            var repeats = Math.min(candidate.bodyCounts().getOrDefault(bigram, 0), REPEAT_LIMIT);
            score += weight * REPEAT_WEIGHT * repeats;
        }
        return score;
    }

    /** 글자·숫자가 이어진 낱말마다 두 글자씩 묶는다. 한 글자 낱말은 묶음이 없어 버린다. */
    static Set<String> bigrams(String text) {
        return countBigrams(text).keySet();
    }

    private static Map<String, Integer> countBigrams(String text) {
        var counts = new HashMap<String, Integer>();
        var word = new StringBuilder();
        for (var codePoint : text.toLowerCase(Locale.ROOT).codePoints().toArray()) {
            if (Character.isLetterOrDigit(codePoint)) {
                word.appendCodePoint(codePoint);
            } else {
                addBigrams(word, counts);
                word.setLength(0);
            }
        }
        addBigrams(word, counts);
        return counts;
    }

    private static void addBigrams(StringBuilder word, Map<String, Integer> counts) {
        for (var i = 0; i + 2 <= word.length(); i++) {
            var bigram = word.substring(i, i + 2);
            if (!QUESTION_FILLER.contains(bigram)) {
                counts.merge(bigram, 1, Integer::sum);
            }
        }
    }

    private record IndexedSection(KnowledgeSection section, Set<String> headline, Set<String> all,
                                  Map<String, Integer> bodyCounts) {
    }

    public record ScoredSection(KnowledgeSection section, double score) {
    }

    public record Ranking(List<ScoredSection> ordered) {
        public double bestScore() {
            return ordered.isEmpty() ? 0.0 : ordered.get(0).score();
        }

        public List<KnowledgeSection> sections(int limit) {
            return ordered.stream().limit(limit).map(ScoredSection::section).toList();
        }
    }
}
