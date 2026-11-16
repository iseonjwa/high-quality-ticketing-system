package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.Concert;
import com.iseonjwa.reservation.concert.domain.entity.SaleStatus;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.entity.Venue;
import com.iseonjwa.reservation.concert.domain.repository.ConcertRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import com.iseonjwa.reservation.concert.presentation.dto.response.ConcertDetailResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 공연 상세의 공연·회차 1초 캐시(ISE-285). 시간은 MutableClock 하나로 옮겨 serverTime과 캐시 만료를 함께 움직인다
class ConcertDetailCacheTest {

    static final long CONCERT_ID = 1L;
    static final long OTHER_CONCERT_ID = 2L;
    static final Instant NOW = Instant.parse("2026-12-01T10:59:59Z");

    final MutableClock clock = new MutableClock(NOW);
    final ConcertRepository concertRepository = mock(ConcertRepository.class);
    final ScheduleRepository scheduleRepository = mock(ScheduleRepository.class);
    final ConcertQueryService service = new ConcertQueryService(concertRepository, scheduleRepository,
            new ConcertDetailReader(concertRepository, scheduleRepository), clock, clock::nanos);

    @Test
    void 일초_안에_같은_공연을_다시_조회하면_DB를_한_번만_읽는다() {
        givenConcert(CONCERT_ID, "공연 A", NOW.plusSeconds(60));

        service.getConcert(CONCERT_ID);
        clock.advance(Duration.ofMillis(999));
        service.getConcert(CONCERT_ID);

        verify(concertRepository, times(1)).findById(CONCERT_ID);
        verify(scheduleRepository, times(1)).findByConcertIdWithVenue(CONCERT_ID);
    }

    @Test
    void 일초가_지나면_DB를_다시_읽어_바뀐_내용이_보인다() {
        givenConcert(CONCERT_ID, "공연 A", NOW.plusSeconds(60));
        service.getConcert(CONCERT_ID);

        givenConcert(CONCERT_ID, "공연 A 제목 수정", NOW.plusSeconds(60));
        clock.advance(Duration.ofSeconds(1));
        ConcertDetailResult result = service.getConcert(CONCERT_ID);

        assertEquals("공연 A 제목 수정", result.getDetail().title());
        verify(concertRepository, times(2)).findById(CONCERT_ID);
    }

    @Test
    void 캐시에서_꺼내도_serverTime과_판매_상태는_요청마다_새로_계산한다() {
        // 오픈 0.5초 전에 한 번 읽고, 캐시가 살아 있는 0.6초 뒤(오픈 0.1초 후)에 다시 조회한다
        Instant openAt = NOW.plusMillis(500);
        givenConcert(CONCERT_ID, "공연 A", openAt);

        ConcertDetailResult before = service.getConcert(CONCERT_ID);
        clock.advance(Duration.ofMillis(600));
        ConcertDetailResult after = service.getConcert(CONCERT_ID);

        verify(concertRepository, times(1)).findById(CONCERT_ID);
        assertEquals(NOW, before.getServerTime());
        assertEquals(SaleStatus.BEFORE_OPEN, saleStatusOf(before));
        assertEquals(NOW.plusMillis(600), after.getServerTime());
        assertEquals(SaleStatus.ON_SALE, saleStatusOf(after));
    }

    @Test
    void 공연_ID가_다르면_캐시가_섞이지_않는다() {
        givenConcert(CONCERT_ID, "공연 A", NOW.plusSeconds(60));
        givenConcert(OTHER_CONCERT_ID, "공연 B", NOW.plusSeconds(60));

        assertEquals("공연 A", service.getConcert(CONCERT_ID).getDetail().title());
        assertEquals("공연 B", service.getConcert(OTHER_CONCERT_ID).getDetail().title());

        verify(concertRepository, times(1)).findById(CONCERT_ID);
        verify(concertRepository, times(1)).findById(OTHER_CONCERT_ID);
    }

    @Test
    void 없는_공연은_캐시하지_않아_만든_직후_바로_보인다() {
        Concert concert = concert(CONCERT_ID, "공연 A");
        when(concertRepository.findById(CONCERT_ID)).thenReturn(Optional.empty(), Optional.of(concert));
        when(scheduleRepository.findByConcertIdWithVenue(CONCERT_ID)).thenReturn(List.of());

        BusinessException notFound = assertThrows(BusinessException.class, () -> service.getConcert(CONCERT_ID));
        assertEquals(CommonErrorCode.NOT_FOUND, notFound.getErrorCode());

        // 시간을 옮기지 않아도 다음 요청이 다시 읽는다
        assertEquals("공연 A", service.getConcert(CONCERT_ID).getDetail().title());
        verify(concertRepository, times(2)).findById(CONCERT_ID);
    }

    private void givenConcert(long concertId, String title, Instant openAt) {
        Concert concert = concert(concertId, title);
        Schedule schedule = Schedule.create(concert, new Venue("공연장"), openAt.plus(Duration.ofDays(7)), openAt,
                null, null);
        when(concertRepository.findById(concertId)).thenReturn(Optional.of(concert));
        when(scheduleRepository.findByConcertIdWithVenue(concertId)).thenReturn(List.of(schedule));
    }

    private static Concert concert(long concertId, String title) {
        Concert concert = new Concert(title, "설명");
        ReflectionTestUtils.setField(concert, "id", concertId);
        return concert;
    }

    // 응답으로 바꾸는 경로까지 지나게 해서, 판매 상태가 응답의 serverTime으로 판정되는지 본다
    private static SaleStatus saleStatusOf(ConcertDetailResult result) {
        return ConcertDetailResponse.from(result).getSchedules().getFirst().getSaleStatus();
    }

    // 테스트 중에 "지금"을 옮길 수 있는 시계. 캐시 만료에 쓰는 나노초도 같은 시각에서 낸다
    static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advance(Duration duration) { this.now = now.plus(duration); }

        long nanos() { return now.getEpochSecond() * 1_000_000_000L + now.getNano(); }

        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
