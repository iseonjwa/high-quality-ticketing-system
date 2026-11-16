package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.reservation.concert.domain.entity.Concert;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.repository.ConcertRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 사용자의 공연 목록·상세 조회(SA 6-5, 11-5). 로그인 없이 호출된다.
 * 지운 공연·회차·공연장은 엔티티의 @SQLRestriction 때문에 조회되지 않는다.
 *
 * <p>Note: 상세에는 트랜잭션을 두지 않는다. DB 읽기는 {@link ConcertDetailReader}만 하고 커넥션도 그동안만 쥔다.
 */
@Service
public class ConcertQueryService {

    // 공연 상세의 공연·회차를 공연마다 1초 동안 같은 값으로 쓴다(ISE-285). 오픈 전 새로고침이 공연 상세에 몰려
    // (11/15 O1 측정에서 Gateway 요청의 73.8%) 요청마다 DB를 두 번 읽던 것을 공연마다 1초에 한 번으로 줄인다.
    // Note: 공연·회차를 고치거나 지워도 최대 1초 동안 이전 내용이 보인다. 판매 상태와 serverTime은 캐시하지 않아
    // 오픈 시각 경계는 정확하다. 예매 가능 여부의 최종 판정은 대기열·선점이 다시 한다
    private static final Duration DETAIL_TTL = Duration.ofSeconds(1);

    private final ConcertRepository concertRepository;
    private final ScheduleRepository scheduleRepository;
    private final ConcertDetailReader concertDetailReader;
    private final Clock clock;

    // 없는 공연(404)은 Reader가 예외로 끝내고, SharedLoadCache는 실패를 남기지 않는다.
    // 그래서 공연을 만든 직후의 조회가 1초 동안 404로 남지 않는다. 없는 ID를 반복 조회하면 매번 DB를 읽는 것은 감수한다
    private final SharedLoadCache<Long, ConcertDetail> detailCache;

    @Autowired
    public ConcertQueryService(ConcertRepository concertRepository, ScheduleRepository scheduleRepository,
                               ConcertDetailReader concertDetailReader, Clock clock) {
        this(concertRepository, scheduleRepository, concertDetailReader, clock, System::nanoTime);
    }

    // 테스트가 캐시 만료 시각을 옮길 때 쓴다
    ConcertQueryService(ConcertRepository concertRepository, ScheduleRepository scheduleRepository,
                        ConcertDetailReader concertDetailReader, Clock clock, LongSupplier nanoTime) {
        this.concertRepository = concertRepository;
        this.scheduleRepository = scheduleRepository;
        this.concertDetailReader = concertDetailReader;
        this.clock = clock;
        this.detailCache = new SharedLoadCache<>(DETAIL_TTL, detail -> true, nanoTime);
    }

    // 공연 목록
    @Transactional(readOnly = true)
    public ConcertListResult getConcerts(int page, int size) {
        List<Concert> concerts = concertRepository.findPage(page, size);
        long totalElements = concertRepository.count();

        // 페이지에 있는 공연들의 남은 회차를 한 번에 읽고, 공연마다 가장 빠른 회차 하나만 남긴다
        List<Long> concertIds = concerts.stream().map(Concert::getId).toList();
        Map<Long, Schedule> nextSchedules = new HashMap<>();
        if (!concertIds.isEmpty()) {
            for (Schedule schedule : scheduleRepository.findUpcomingByConcertIds(concertIds, clock.instant())) {
                // 시작 시각 오름차순이라 공연마다 처음 나온 회차가 가장 빠르다
                nextSchedules.putIfAbsent(schedule.getConcert().getId(), schedule);
            }
        }
        return new ConcertListResult(concerts, nextSchedules, page, size, totalElements);
    }

    // 공연 상세. 공연·회차는 1초 캐시에서, serverTime은 요청마다 시계에서 읽는다
    public ConcertDetailResult getConcert(Long concertId) {
        ConcertDetail detail = detailCache.get(concertId, () -> concertDetailReader.read(concertId));
        return new ConcertDetailResult(detail, clock.instant());
    }
}
