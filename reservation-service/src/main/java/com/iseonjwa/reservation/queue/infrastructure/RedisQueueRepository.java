package com.iseonjwa.reservation.queue.infrastructure;

import com.iseonjwa.reservation.queue.domain.EnterOutcome;
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

    private final StringRedisTemplate stringRedisTemplate;


    @Override
    public EnterOutcome enter(long scheduleId,
                              long userId, long openAtEpochMilli, long tokenTtlMillis, long keyExpireAtEpochSecond) {
        List r = stringRedisTemplate.execute(ENTER, QueueKeys.of(scheduleId).enterKeys(),
                String.valueOf(userId), String.valueOf(openAtEpochMilli),
                String.valueOf(tokenTtlMillis), String.valueOf(keyExpireAtEpochSecond));

        EnterOutcome.Type type = EnterOutcome.Type.valueOf((String) r.get(0));
        if(r.size() == 1) {
            return new EnterOutcome(type, null, null);
        }
        return new EnterOutcome(type, (Long) r.get(1), (Long) r.get(2));
    }
}
