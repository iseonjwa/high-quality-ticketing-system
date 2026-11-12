package com.iseonjwa.reservation.concert.application;

import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository.SeatLayoutRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

/**
 * 회차 좌석 배치 캐시(P4b). 측정 브랜치 전용으로 저장소를 로컬 메모리와 Redis 중에서 고른다(STEPS.md "P4b 배치 캐시 로컬 대 Redis").
 *
 * <ul>
 *   <li>local(기본): 인스턴스 메모리에 만료 없이 둔다. 같은 회차를 동시에 처음 읽으면 한 요청만 DB에 간다</li>
 *   <li>redis: 배치를 JSON 문자열 하나로 Redis에 두고, 요청마다 GET해 역직렬화한다. 없으면 DB에서 읽어 넣는다</li>
 * </ul>
 */
@Component
public class SeatLayoutCache {

    private static final String KEY_PREFIX = "k6:seatmap:layout:";
    // Note: 공유 Redis는 maxmemory 128MB, noeviction이다. 회차마다 키가 쌓여 꽉 차면 대기열·인증 쓰기까지 실패하므로 만료를 둔다
    private static final Duration REDIS_TTL = Duration.ofHours(1);
    private static final TypeReference<List<SeatLayoutRow>> ROWS = new TypeReference<>() {};

    private final SeatMapReader seatMapReader;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean useRedis;

    private final SharedLoadCache<Long, List<SeatLayoutRow>> localCache =
            new SharedLoadCache<>(null, layout -> !layout.isEmpty());

    public SeatLayoutCache(SeatMapReader seatMapReader, StringRedisTemplate redis, ObjectMapper objectMapper,
            @Value("${seatmap.layout-cache:local}") String store) {
        this.seatMapReader = seatMapReader;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.useRedis = "redis".equals(store);
    }

    public List<SeatLayoutRow> get(Long scheduleId) {
        if (!useRedis) {
            return localCache.get(scheduleId, () -> seatMapReader.readLayout(scheduleId));
        }
        String key = KEY_PREFIX + scheduleId;
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return objectMapper.readValue(cached, ROWS);
        }
        // Note: 처음 읽는 순간 동시 요청이 함께 DB에 갈 수 있다. 워밍업에서 한 번 일어나고 측정 구간에는 없다
        List<SeatLayoutRow> layout = seatMapReader.readLayout(scheduleId);
        if (!layout.isEmpty()) {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(layout), REDIS_TTL);
        }
        return layout;
    }
}
