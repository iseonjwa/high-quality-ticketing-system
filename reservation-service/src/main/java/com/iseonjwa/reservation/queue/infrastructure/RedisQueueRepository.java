package com.iseonjwa.reservation.queue.infrastructure;

import com.iseonjwa.reservation.queue.domain.EnterOutcome;
import com.iseonjwa.reservation.queue.domain.PositionOutcome;
import com.iseonjwa.reservation.queue.domain.QueueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class RedisQueueRepository implements QueueRepository {

    private final RedisScript<List> ENTER =
            RedisScript.of(new ClassPathResource("/redis/queue/enter.lua"), List.class);
    private final RedisScript<List> POSITION =
            RedisScript.of(new ClassPathResource("/redis/queue/position.lua"), List.class);

    private final StringRedisTemplate stringRedisTemplate;


    @Override
    public EnterOutcome enter(long scheduleId,
                              long userId, long openAtEpochMilli, long tokenTtlMillis, long keyExpireAtEpochSecond) {
        List o = stringRedisTemplate.execute(ENTER, QueueKeys.of(scheduleId).enterKeys(),
                String.valueOf(userId), String.valueOf(openAtEpochMilli),
                String.valueOf(tokenTtlMillis), String.valueOf(keyExpireAtEpochSecond));

        EnterOutcome.Type type = EnterOutcome.Type.valueOf((String) o.get(0));
        if(o.size() == 1) {
            return new EnterOutcome(type, null, null);
        }
        return new EnterOutcome(type, (Long) o.get(1), (Long) o.get(2));
    }

    @Override
    public PositionOutcome position(long scheduleId, long userId, long tokenTtlMillis) {
        List o = stringRedisTemplate.execute(POSITION, QueueKeys.of(scheduleId).positionKeys(),
                String.valueOf(userId), String.valueOf(tokenTtlMillis));

        PositionOutcome.Type type = PositionOutcome.Type.valueOf((String) o.get(0));
        if (o.size() == 1) {
            return new PositionOutcome(type, null, null);
        }
        return new PositionOutcome(type, (Long) o.get(1), (Long) o.get(2));
    }


}
