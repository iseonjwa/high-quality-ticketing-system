package com.iseonjwa.reservation.concert.presentation;

import com.iseonjwa.reservation.concert.application.ScheduleSeatsResult;
import com.iseonjwa.reservation.concert.presentation.dto.response.SeatSectionResponse;
import org.springframework.stereotype.Component;
import tools.jackson.core.io.SerializedString;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.util.RawValue;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 회차 좌석 응답의 좌석 배열(sections)을 JSON으로 직렬화해 둔 캐시(ISE-264).
 *
 * <p>Note: 15,000석이면 sections가 약 830KB라 요청마다 객체를 만들고 직렬화하는 데 CPU를 많이 쓴다(P4c JFR: Jackson 약 32%).
 * 서비스는 같은 배치·같은 1초 캐시 값이면 같은 결과 객체를 돌려주므로, 결과 객체가 같으면 직렬화한 바이트를 그대로 쓴다.
 * 그래서 낡을 수 있는 정도는 선점 가능 여부 1초 캐시(ISE-205)와 같고 따로 TTL을 두지 않는다.
 * traceId와 serverTime은 요청마다 바깥 봉투에 새로 쓴다.
 */
@Component
public class ScheduleSeatSectionsJsonCache {

    private final ObjectMapper mapper;
    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();

    // Spring이 응답을 쓸 때 쓰는 매퍼를 받아, 미리 쓴 바이트가 그 자리에서 직렬화한 것과 같게 한다
    public ScheduleSeatSectionsJsonCache(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // Note: compute는 같은 회차의 다른 요청을 직렬화가 끝날 때까지 기다리게 해서, 1초가 바뀌는 순간 요청이 몰려도 직렬화는 한 번이다
    public RawValue get(Long scheduleId, ScheduleSeatsResult result) {
        return entries.compute(scheduleId, (id, old) ->
                old != null && old.result() == result ? old : new Entry(result, serialize(result))).sections();
    }

    private RawValue serialize(ScheduleSeatsResult result) {
        SerializedString json = new SerializedString(mapper.writeValueAsString(SeatSectionResponse.listOf(result)));
        // UTF-8 바이트를 미리 만들어 둔다. 요청마다 문자열을 인코딩하지 않고 바이트를 복사만 한다
        json.asUnquotedUTF8();
        return new RawValue(json);
    }

    private record Entry(ScheduleSeatsResult result, RawValue sections) {
    }
}
