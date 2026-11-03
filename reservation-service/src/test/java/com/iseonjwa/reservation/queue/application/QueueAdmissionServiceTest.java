package com.iseonjwa.reservation.queue.application;

import com.iseonjwa.reservation.queue.domain.EnterOutcome;
import com.iseonjwa.reservation.queue.domain.PositionOutcome;
import com.iseonjwa.reservation.queue.domain.QueueRepository;
import com.iseonjwa.reservation.queue.domain.ScheduleReader;
import com.iseonjwa.reservation.queue.domain.ScheduleTimes;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 입장 처리: 판매 중인 회차마다 admit을 부르고, 한 회차가 실패해도 나머지는 계속한다
class QueueAdmissionServiceTest {

    static final Instant STARTS_AT = Instant.parse("2026-11-20T19:00:00Z");

    // 순서: admitPerSecond, entryTokenTtl, pollMin, pollMax, pollJitterRatio, cleanupLimit, keyRetentionAfterStart
    final QueueProperties properties = new QueueProperties(
            50, Duration.ofMinutes(10), Duration.ofSeconds(3), Duration.ofSeconds(10), 0.2, 500, Duration.ofHours(1));

    final RecordingQueueRepository repository = new RecordingQueueRepository();

    @Test
    void 판매_중인_회차마다_설정값으로_admit을_부른다() {
        var service = service(List.of(schedule(31L), schedule(32L)));

        service.admitOnSale();

        assertEquals(List.of(31L, 32L), repository.admittedScheduleIds());
        Admit first = repository.calls.get(0);
        assertEquals(50, first.admitPerSecond());
        assertEquals(600_000L, first.tokenTtlMillis());
        assertEquals(500, first.cleanupLimit());
        assertEquals(STARTS_AT.plus(Duration.ofHours(1)).getEpochSecond(), first.keyExpireAtEpochSecond());
    }

    @Test
    void 한_회차가_실패해도_다음_회차는_처리한다() {
        repository.failingScheduleIds = Set.of(31L);
        var service = service(List.of(schedule(31L), schedule(32L)));

        service.admitOnSale(); // 예외가 밖으로 나오지 않아야 한다

        assertEquals(List.of(31L, 32L), repository.admittedScheduleIds());
    }

    @Test
    void 판매_중인_회차가_없으면_아무것도_하지_않는다() {
        var service = service(List.of());

        service.admitOnSale();

        assertTrue(repository.calls.isEmpty());
    }

    // --- 도우미 ---

    QueueAdmissionService service(List<ScheduleTimes> onSale) {
        return new QueueAdmissionService(new FixedScheduleReader(onSale), repository, properties);
    }

    static ScheduleTimes schedule(long id) {
        return new ScheduleTimes(id, STARTS_AT.minus(Duration.ofDays(10)), STARTS_AT);
    }

    record Admit(long scheduleId, int admitPerSecond, long tokenTtlMillis, int cleanupLimit, long keyExpireAtEpochSecond) {}

    // 받은 admit 호출을 기록하고, 지정한 회차에서는 Redis 오류처럼 예외를 던진다
    static class RecordingQueueRepository implements QueueRepository {
        final List<Admit> calls = new ArrayList<>();
        Set<Long> failingScheduleIds = Set.of();

        @Override
        public void admit(long scheduleId, int admitPerSecond, long tokenTtlMillis, int cleanupLimit, long keyExpireAtEpochSecond) {
            calls.add(new Admit(scheduleId, admitPerSecond, tokenTtlMillis, cleanupLimit, keyExpireAtEpochSecond));
            if (failingScheduleIds.contains(scheduleId)) {
                throw new IllegalStateException("Redis 오류");
            }
        }

        List<Long> admittedScheduleIds() {
            return calls.stream().map(Admit::scheduleId).toList();
        }

        @Override
        public EnterOutcome enter(long scheduleId, long userId, long openAtEpochMilli, long tokenTtlMillis, long keyExpireAtEpochSecond) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PositionOutcome position(long scheduleId, long userId, long tokenTtlMillis) {
            throw new UnsupportedOperationException();
        }
    }

    // 정해 둔 회차를 판매 중으로 돌려주는 가짜 창구
    record FixedScheduleReader(List<ScheduleTimes> onSale) implements ScheduleReader {
        @Override public List<ScheduleTimes> findOnSale() { return onSale; }
        @Override public Optional<ScheduleTimes> findById(long id) { throw new UnsupportedOperationException(); }
    }
}
