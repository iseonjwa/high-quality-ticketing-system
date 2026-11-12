package com.iseonjwa.payment.reconciliation;

import com.iseonjwa.payment.reconciliation.application.ReservationLookup;
import com.iseonjwa.payment.reconciliation.application.ReservationLookupException;
import com.iseonjwa.payment.reconciliation.application.ReservationViewLoader;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationPolicy;
import com.iseonjwa.payment.reconciliation.domain.ReconciliationRound;
import com.iseonjwa.payment.reconciliation.domain.ReservationSnapshot;
import com.iseonjwa.payment.reconciliation.domain.ReservationStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 스냅숏 로더의 묶음·쪽 나누기와 회차 문맥의 한 번 호출·실패 기억. DB와 HTTP 없이 확인한다. */
class ReservationViewLoaderTest {
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    private static final ReconciliationPolicy POLICY =
            new ReconciliationPolicy(Duration.ofMinutes(10), Duration.ofDays(7), Duration.ofHours(24));

    private static ReservationViewLoader.Limits limits(int batch) {
        return new ReservationViewLoader.Limits(batch, 2, 5, 0.1, 200);
    }

    private static ReservationSnapshot snapshot(long id) {
        return new ReservationSnapshot(id, 1, ReservationStatus.CONFIRMED, 100, id, null, NOW);
    }

    /** 요청한 ID를 짝수만 있다고 답하고, 변경 목록은 주어진 쪽 목록을 돌려준다. */
    private static class InMemoryLookup implements ReservationLookup {
        final List<Integer> batches = new ArrayList<>();
        final List<String> cursors = new ArrayList<>();
        final List<ChangesPage> pages;

        InMemoryLookup(List<ChangesPage> pages) {
            this.pages = pages;
        }

        @Override
        public List<ReservationSnapshot> findByIds(Collection<Long> ids) {
            batches.add(ids.size());
            var found = new ArrayList<>(ids.stream().filter(id -> id % 2 == 0).map(ReservationViewLoaderTest::snapshot).toList());
            found.add(snapshot(9_999_999L)); // 묻지 않은 예매가 섞여 와도 무시한다
            return found;
        }

        @Override
        public ChangesPage findChanges(Instant from, Instant to, String cursor) {
            cursors.add(cursor);
            return pages.get(cursor == null ? 0 : Integer.parseInt(cursor));
        }
    }

    @Test
    void 대상_예매를_묶음_크기로_나눠_묻고_응답에서_빠진_예매는_없는_것으로_둔다() {
        var lookup = new InMemoryLookup(List.of(new ReservationLookup.ChangesPage(List.of(), null)));
        var ids = Set.copyOf(LongStream.rangeClosed(1, 250).boxed().toList());
        var loader = new ReservationViewLoader(since -> ids, lookup, new ReservationViewLoader.Limits(100, 2, 1000, 1.0, 200));

        var view = loader.load(NOW, POLICY);

        assertEquals(List.of(100, 100, 50), lookup.batches);
        assertTrue(view.wasRequested(1));
        assertNull(view.find(1), "홀수는 응답에서 빠졌으므로 예매 없음이다");
        assertEquals(2, view.find(2).reservationId());
        assertFalse(view.wasRequested(9_999_999L));
        assertNull(view.find(9_999_999L), "묻지 않은 예매는 담지 않는다");
    }

    @Test
    void 변경_목록은_다음_커서가_없을_때까지_읽는다() {
        var lookup = new InMemoryLookup(List.of(
                new ReservationLookup.ChangesPage(List.of(snapshot(1)), "1"),
                new ReservationLookup.ChangesPage(List.of(snapshot(2)), "2"),
                new ReservationLookup.ChangesPage(List.of(snapshot(3)), null)));
        var loader = new ReservationViewLoader(since -> Set.of(), lookup, limits(100));

        var view = loader.load(NOW, POLICY);

        assertEquals(List.of(1L, 2L, 3L), view.changes().stream().map(ReservationSnapshot::reservationId).toList());
        assertEquals(java.util.Arrays.asList(null, "1", "2"), lookup.cursors);
    }

    @Test
    void 커서가_되풀이되면_끝나지_않으므로_실패한다() {
        var lookup = new InMemoryLookup(List.of(
                new ReservationLookup.ChangesPage(List.of(snapshot(1)), "1"),
                new ReservationLookup.ChangesPage(List.of(snapshot(2)), "1")));
        var loader = new ReservationViewLoader(since -> Set.of(), lookup, limits(100));

        assertThrows(ReservationLookupException.class, () -> loader.load(NOW, POLICY));
    }

    @Test
    void 회차_문맥은_스냅숏을_한_번만_가져온다() {
        var calls = new AtomicInteger();
        var lookup = new InMemoryLookup(List.of(new ReservationLookup.ChangesPage(List.of(), null)));
        var loader = new ReservationViewLoader(since -> Set.of(2L), lookup, limits(100));
        var round = new ReconciliationRound(NOW, POLICY, () -> {
            calls.incrementAndGet();
            return loader.load(NOW, POLICY);
        });

        var first = round.reservations();
        var second = round.reservations();

        assertSame(first, second);
        assertEquals(1, calls.get());
        assertEquals(1, lookup.batches.size());
    }

    @Test
    void 회차_문맥은_실패도_기억해_다시_부르지_않는다() {
        var calls = new AtomicInteger();
        var round = new ReconciliationRound(NOW, POLICY, () -> {
            calls.incrementAndGet();
            throw new ReservationLookupException("실패");
        });

        assertThrows(ReservationLookupException.class, round::reservations);
        assertThrows(IllegalStateException.class, round::reservations);
        assertThrows(IllegalStateException.class, round::reservations);
        assertEquals(1, calls.get());
    }

    private static ReservationViewLoader loader(Set<Long> ids, ReservationLookup lookup, ReservationViewLoader.Limits limits) {
        return new ReservationViewLoader(since -> ids, lookup, limits);
    }

    private static ReservationLookup answering(java.util.function.Predicate<Long> exists, List<ReservationLookup.ChangesPage> pages,
            List<List<Long>> asked) {
        return new ReservationLookup() {
            @Override
            public List<ReservationSnapshot> findByIds(Collection<Long> ids) {
                asked.add(List.copyOf(ids));
                return ids.stream().filter(exists).map(ReservationViewLoaderTest::snapshot).toList();
            }

            @Override
            public ChangesPage findChanges(Instant from, Instant to, String cursor) {
                return pages.get(cursor == null ? 0 : Integer.parseInt(cursor));
            }
        };
    }

    @Test
    void 변경_목록에_나온_예매는_스냅숏에_합치고_묶음_조회에서_뺀다() {
        var asked = new ArrayList<List<Long>>();
        var lookup = answering(id -> true, List.of(new ReservationLookup.ChangesPage(List.of(snapshot(2)), null)), asked);

        var view = loader(Set.of(1L, 2L, 3L), lookup, limits(100)).load(NOW, POLICY);

        assertEquals(List.of(List.of(1L, 3L)), asked, "2는 변경 목록으로 이미 받았다");
        assertTrue(view.wasRequested(2));
        assertEquals(2, view.find(2).reservationId());
        assertEquals(3, view.find(3).reservationId());
    }

    @Test
    void 변경_목록에만_있는_예매도_요청한_것으로_본다() {
        var lookup = answering(id -> true, List.of(new ReservationLookup.ChangesPage(List.of(snapshot(7)), null)), new ArrayList<>());

        var view = loader(Set.of(), lookup, limits(100)).load(NOW, POLICY);

        assertTrue(view.wasRequested(7));
        assertEquals(7, view.find(7).reservationId());
    }

    @Test
    void 빈_쪽이면_새_커서를_줘도_거기서_끝낸다() {
        var empty = new ReservationLookup.ChangesPage(List.of(), "next");
        var lookup = answering(id -> true, List.of(new ReservationLookup.ChangesPage(List.of(snapshot(1)), "1"), empty), new ArrayList<>());
        var cursors = new ArrayList<String>();
        var counting = new ReservationLookup() {
            @Override
            public List<ReservationSnapshot> findByIds(Collection<Long> ids) {
                return lookup.findByIds(ids);
            }

            @Override
            public ChangesPage findChanges(Instant from, Instant to, String cursor) {
                cursors.add(cursor);
                return lookup.findChanges(from, to, cursor);
            }
        };

        var view = loader(Set.of(), counting, limits(100)).load(NOW, POLICY);

        assertEquals(1, view.changes().size());
        assertEquals(java.util.Arrays.asList(null, "1"), cursors);
    }

    /** 호출한 구간과 커서를 기록하고, 끝나지 않는 변경 목록(쪽마다 항목 하나, 항상 다음 커서)을 돌려준다. */
    private static class EndlessLookup implements ReservationLookup {
        record Call(Instant from, Instant to, String cursor) {}

        final List<Call> calls = new ArrayList<>();
        private final int totalPages;

        EndlessLookup(int totalPages) {
            this.totalPages = totalPages;
        }

        @Override
        public List<ReservationSnapshot> findByIds(Collection<Long> ids) {
            return List.of();
        }

        @Override
        public ChangesPage findChanges(Instant from, Instant to, String cursor) {
            calls.add(new Call(from, to, cursor));
            var index = cursor == null ? 1 : Integer.parseInt(cursor) + 1;
            return new ChangesPage(List.of(snapshot(index)), index >= totalPages ? null : String.valueOf(index));
        }
    }

    private static ReservationViewLoader budgetLoader(ReservationLookup lookup, int budget) {
        return loader(Set.of(), lookup, new ReservationViewLoader.Limits(100, 2, 5, 0.1, budget));
    }

    private static final Duration OVERLAP = Duration.ofMinutes(11);

    @Test
    void 첫_회차는_정책_기간_전체를_읽는다() {
        var lookup = new EndlessLookup(1);

        budgetLoader(lookup, 200).load(NOW, POLICY);

        assertEquals(new EndlessLookup.Call(NOW.minus(Duration.ofDays(7)), NOW, null), lookup.calls.get(0));
    }

    @Test
    void 예산을_넘으면_실패하지_않고_읽은_항목으로_뷰를_만든다() {
        var lookup = new EndlessLookup(10);

        var view = budgetLoader(lookup, 3).load(NOW, POLICY);

        assertEquals(3, lookup.calls.size());
        assertEquals(List.of(1L, 2L, 3L), view.changes().stream().map(ReservationSnapshot::reservationId).toList());
        assertEquals("3", view.next().remaining().cursor());
        assertNull(view.next().watermark());
    }

    @Test
    void 확정한_뒤_다음_회차는_같은_구간을_남은_커서부터_이어_읽는다() {
        var lookup = new EndlessLookup(10);
        var loader = budgetLoader(lookup, 3);
        loader.commit(loader.load(NOW, POLICY).next());
        lookup.calls.clear();

        var view = loader.load(NOW.plus(Duration.ofMinutes(5)), POLICY);

        assertEquals(new EndlessLookup.Call(NOW.minus(Duration.ofDays(7)), NOW, "3"), lookup.calls.get(0));
        assertEquals("6", view.next().remaining().cursor());
    }

    @Test
    void 구간을_다_읽고_확정하면_다음_회차는_이전_끝에서_허용_지연_더하기_1분_앞부터_읽는다() {
        var lookup = new EndlessLookup(2);
        var loader = budgetLoader(lookup, 200);
        var first = loader.load(NOW, POLICY);
        assertEquals(NOW, first.next().watermark());
        assertNull(first.next().remaining());
        loader.commit(first.next());
        lookup.calls.clear();
        var later = NOW.plus(Duration.ofMinutes(5));

        loader.load(later, POLICY);

        assertEquals(new EndlessLookup.Call(NOW.minus(OVERLAP), later, null), lookup.calls.get(0));
    }

    @Test
    void 확정하지_않으면_다음_회차가_처음_커서부터_다시_읽는다() {
        var lookup = new EndlessLookup(10);
        var loader = budgetLoader(lookup, 3);
        loader.load(NOW, POLICY); // 규칙 실패를 흉내: commit하지 않는다
        lookup.calls.clear();

        loader.load(NOW.plus(Duration.ofMinutes(5)), POLICY);

        assertEquals(NOW.minus(Duration.ofDays(7)).plus(Duration.ofMinutes(5)), lookup.calls.get(0).from());
        assertNull(lookup.calls.get(0).cursor());
    }

    @Test
    void 남은_구간이_7일_밖으로_밀려나면_버리고_새로_읽는다() {
        var lookup = new EndlessLookup(10);
        var loader = budgetLoader(lookup, 3);
        loader.commit(loader.load(NOW, POLICY).next()); // 남은 구간 to = NOW
        lookup.calls.clear();
        var later = NOW.plus(Duration.ofDays(7)).plus(Duration.ofMinutes(1));

        loader.load(later, POLICY);

        assertEquals(new EndlessLookup.Call(later.minus(Duration.ofDays(7)), later, null), lookup.calls.get(0));
    }

    @Test
    void 워터마크가_7일보다_오래됐으면_from을_7일_전으로_자른다() {
        var lookup = new EndlessLookup(1);
        var loader = budgetLoader(lookup, 200);
        loader.commit(loader.load(NOW, POLICY).next()); // 워터마크 = NOW
        lookup.calls.clear();
        var later = NOW.plus(Duration.ofDays(30));

        loader.load(later, POLICY);

        assertEquals(later.minus(Duration.ofDays(7)), lookup.calls.get(0).from());
    }

    @Test
    void 이어_읽다_다_읽으면_워터마크가_그_구간의_끝이_된다() {
        var lookup = new EndlessLookup(4);
        var loader = budgetLoader(lookup, 3);
        loader.commit(loader.load(NOW, POLICY).next());

        var view = loader.load(NOW.plus(Duration.ofMinutes(5)), POLICY);

        assertEquals(NOW, view.next().watermark());
        assertNull(view.next().remaining());
    }

    @Test
    void 이어_읽을_때도_커서가_되풀이되면_실패한다() {
        var lookup = new ReservationLookup() {
            @Override
            public List<ReservationSnapshot> findByIds(Collection<Long> ids) {
                return List.of();
            }

            @Override
            public ChangesPage findChanges(Instant from, Instant to, String cursor) {
                return new ChangesPage(List.of(snapshot(1)), "same");
            }
        };
        var loader = budgetLoader(lookup, 200);

        assertThrows(ReservationLookupException.class, () -> loader.load(NOW, POLICY));
    }

    @Test
    void 묶음_하나가_통째로_비면_조회_실패다() {
        var lookup = answering(id -> false, List.of(new ReservationLookup.ChangesPage(List.of(), null)), new ArrayList<>());

        assertThrows(ReservationLookupException.class, () -> loader(Set.of(1L, 2L), lookup, limits(100)).load(NOW, POLICY));
    }

    @Test
    void 한_건만_물어_비는_것은_정상_예매_없음이다() {
        var lookup = answering(id -> false, List.of(new ReservationLookup.ChangesPage(List.of(), null)), new ArrayList<>());

        var view = loader(Set.of(1L), lookup, limits(100)).load(NOW, POLICY);

        assertTrue(view.wasRequested(1));
        assertNull(view.find(1));
    }

    @Test
    void 회차_전체_누락이_5건_이상이고_10퍼센트를_넘으면_조회_실패다() {
        // 100건 중 6건 누락(6%)은 정상, 100건 중 12건 누락(12%)은 실패
        var ids = Set.copyOf(LongStream.rangeClosed(1, 100).boxed().toList());

        var sixMissing = answering(id -> id > 6, List.of(new ReservationLookup.ChangesPage(List.of(), null)), new ArrayList<>());
        loader(ids, sixMissing, limits(100)).load(NOW, POLICY);

        var twelveMissing = answering(id -> id > 12, List.of(new ReservationLookup.ChangesPage(List.of(), null)), new ArrayList<>());
        assertThrows(ReservationLookupException.class, () -> loader(ids, twelveMissing, limits(100)).load(NOW, POLICY));
    }

    @Test
    void 누락이_5건_미만이면_비율이_커도_정상이다() {
        var lookup = answering(id -> id > 4, List.of(new ReservationLookup.ChangesPage(List.of(), null)), new ArrayList<>());

        loader(Set.of(1L, 2L, 3L, 4L, 5L), lookup, limits(100)).load(NOW, POLICY);
    }
}
