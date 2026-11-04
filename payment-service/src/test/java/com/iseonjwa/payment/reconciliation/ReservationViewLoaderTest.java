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
        return new ReservationViewLoader.Limits(batch, 2, 5, 0.1, 1000);
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
        var loader = new ReservationViewLoader(since -> ids, lookup, new ReservationViewLoader.Limits(100, 2, 1000, 1.0, 1000));

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

    @Test
    void 최대_쪽_수를_넘으면_조회_실패다() {
        var lookup = new ReservationLookup() {
            private int next;

            @Override
            public List<ReservationSnapshot> findByIds(Collection<Long> ids) {
                return List.of();
            }

            @Override
            public ChangesPage findChanges(Instant from, Instant to, String cursor) {
                return new ChangesPage(List.of(snapshot(++next)), String.valueOf(next));
            }
        };

        assertThrows(ReservationLookupException.class,
                () -> loader(Set.of(), lookup, new ReservationViewLoader.Limits(100, 2, 5, 0.1, 3)).load(NOW, POLICY));
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
