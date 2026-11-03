package com.iseonjwa.reservation.queue.infrastructure;

import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import com.iseonjwa.reservation.queue.domain.ScheduleTimes;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 회차 메모(캐시): DB는 refresh에서만 읽고, 판매 중 판단과 회차 하나 조회는 메모를 먼저 본다
class CachedScheduleReaderTest {

    static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    static final Duration LOOK_AHEAD = Duration.ofHours(1);

    final FakeScheduleRepository repository = new FakeScheduleRepository();
    final MutableClock clock = new MutableClock(NOW);
    final CachedScheduleReader reader = new CachedScheduleReader(repository, clock, LOOK_AHEAD);

    @Test
    void refresh_전에는_판매_중인_회차가_없다() {
        repository.open(schedule(31L, NOW.minusSeconds(60), NOW.plusSeconds(3600)));

        assertTrue(reader.findOnSale().isEmpty());
    }

    @Test
    void refresh는_지금부터_lookAhead까지_열리는_회차를_읽는다() {
        reader.refresh();

        assertEquals(NOW.plus(LOOK_AHEAD), repository.lastUntil);
        assertEquals(NOW, repository.lastNow);
    }

    @Test
    void 메모_중_지금_판매_중인_회차만_돌려준다() {
        repository.open(schedule(31L, NOW.minusSeconds(60), NOW.plusSeconds(3600)));    // 판매 중
        repository.open(schedule(32L, NOW.plusSeconds(20 * 60), NOW.plusSeconds(7200))); // 20분 뒤 오픈
        reader.refresh();

        assertEquals(List.of(31L), ids(reader.findOnSale()));
    }

    @Test
    void 오픈_시각이_되면_DB를_다시_읽지_않아도_판매_중에_들어간다() {
        repository.open(schedule(32L, NOW.plusSeconds(20 * 60), NOW.plusSeconds(7200)));
        reader.refresh();
        int callsAfterRefresh = repository.findOpenByCalls;

        clock.set(NOW.plusSeconds(20 * 60));

        assertEquals(List.of(32L), ids(reader.findOnSale()));
        assertEquals(callsAfterRefresh, repository.findOpenByCalls);
    }

    @Test
    void 메모에_있는_회차는_DB를_읽지_않고_찾는다() {
        repository.open(schedule(31L, NOW.minusSeconds(60), NOW.plusSeconds(3600)));
        reader.refresh();

        Optional<ScheduleTimes> found = reader.findById(31L);

        assertEquals(31L, found.orElseThrow().id());
        assertEquals(0, repository.findByIdCalls);
    }

    @Test
    void 메모에_없는_회차는_DB에서_찾는다() {
        repository.store(schedule(40L, NOW.plus(Duration.ofDays(3)), NOW.plus(Duration.ofDays(10)))); // 메모 범위 밖
        reader.refresh();

        Optional<ScheduleTimes> found = reader.findById(40L);

        assertEquals(40L, found.orElseThrow().id());
        assertEquals(1, repository.findByIdCalls);
    }

    @Test
    void DB에도_없는_회차는_비어_있다() {
        reader.refresh();

        assertTrue(reader.findById(99L).isEmpty());
    }

    @Test
    void refresh가_실패하면_이전_메모를_그대로_쓴다() {
        repository.open(schedule(31L, NOW.minusSeconds(60), NOW.plusSeconds(3600)));
        reader.refresh();
        repository.failNext = true;

        try {
            reader.refresh();
        } catch (RuntimeException ignored) {
            // @Scheduled는 예외를 로그로 남기고 다음 주기에 다시 실행한다
        }

        assertEquals(List.of(31L), ids(reader.findOnSale()));
    }

    // --- 도우미 ---

    static Schedule schedule(long id, Instant openAt, Instant startsAt) {
        Schedule schedule = Schedule.create(null, null, startsAt, openAt, null, null);
        ReflectionTestUtils.setField(schedule, "id", id); // 저장 전이라 id가 없으므로 직접 넣는다
        return schedule;
    }

    static List<Long> ids(List<ScheduleTimes> schedules) {
        return schedules.stream().map(ScheduleTimes::id).toList();
    }

    // 대기열이 쓰는 조회만 흉내 낸다. 나머지는 쓰면 안 되므로 예외를 던진다
    static class FakeScheduleRepository implements ScheduleRepository {
        final Map<Long, Schedule> all = new HashMap<>();
        final List<Schedule> openWindow = new ArrayList<>();
        Instant lastUntil;
        Instant lastNow;
        int findOpenByCalls;
        int findByIdCalls;
        boolean failNext;

        void open(Schedule schedule) {   // findOpenBy 결과에 들어가는 회차
            openWindow.add(schedule);
            all.put(schedule.getId(), schedule);
        }

        void store(Schedule schedule) {  // DB에는 있지만 findOpenBy 범위 밖인 회차
            all.put(schedule.getId(), schedule);
        }

        @Override
        public List<Schedule> findOpenBy(Instant until, Instant now) {
            findOpenByCalls++;
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("DB 연결 실패");
            }
            lastUntil = until;
            lastNow = now;
            return List.copyOf(openWindow);
        }

        @Override
        public Optional<Schedule> findById(Long id) {
            findByIdCalls++;
            return Optional.ofNullable(all.get(id));
        }

        @Override public Schedule save(Schedule schedule) { throw new UnsupportedOperationException(); }
        @Override public Optional<Schedule> findByIdForUpdate(Long id) { throw new UnsupportedOperationException(); }
        @Override public List<Schedule> findUpcomingByConcertIds(Collection<Long> concertIds, Instant now) { throw new UnsupportedOperationException(); }
        @Override public List<Schedule> findByConcertIdWithVenue(Long concertId) { throw new UnsupportedOperationException(); }
    }

    // 테스트 중에 "지금"을 옮길 수 있는 시계
    static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void set(Instant now) { this.now = now; }

        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
