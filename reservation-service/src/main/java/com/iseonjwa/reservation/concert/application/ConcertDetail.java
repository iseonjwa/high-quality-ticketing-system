package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.reservation.concert.domain.entity.Concert;
import com.iseonjwa.reservation.concert.domain.entity.SaleStatus;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;

import java.time.Instant;
import java.util.List;

/**
 * 공연 상세 중 1초 동안 그대로 써도 되는 부분(공연·회차·공연장 이름). 시각에 따라 바뀌는 판매 상태는 담지 않는다.
 *
 * <p>Note: 엔티티가 아니라 불변 값이라 트랜잭션이 끝난 뒤 여러 요청이 같은 객체를 같이 읽어도 된다.
 * 엔티티를 캐시하면 영속성 컨텍스트 밖에서 지연 로딩이 터지거나, 한 요청이 고친 필드가 다른 요청에 보인다.
 *
 * @param schedules 회차들(시작 시각 오름차순)
 */
public record ConcertDetail(Long concertId, String title, String description, List<ScheduleDetail> schedules) {

    public ConcertDetail {
        schedules = List.copyOf(schedules);
    }

    // 트랜잭션 안에서 부른다. 회차의 공연장은 미리 읽어 둔 것이어야 한다(findByConcertIdWithVenue)
    static ConcertDetail of(Concert concert, List<Schedule> schedules) {
        List<ScheduleDetail> details = schedules.stream()
                .map(schedule -> new ScheduleDetail(schedule.getId(), schedule.getVenue().getName(),
                        schedule.getStartsAt(), schedule.getOpenAt(), schedule.getMaxPerUser()))
                .toList();
        return new ConcertDetail(concert.getId(), concert.getTitle(), concert.getDescription(), details);
    }

    // 공연 상세 안의 회차 한 줄
    public record ScheduleDetail(Long scheduleId, String venueName, Instant startsAt, Instant openAt, int maxPerUser) {

        public SaleStatus saleStatusAt(Instant now) {
            return SaleStatus.at(openAt, startsAt, now);
        }
    }
}
