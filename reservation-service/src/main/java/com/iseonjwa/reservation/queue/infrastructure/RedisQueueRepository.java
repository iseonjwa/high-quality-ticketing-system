package com.iseonjwa.reservation.queue.infrastructure;

import com.iseonjwa.reservation.queue.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.function.Supplier;

@Repository
@RequiredArgsConstructor
public class RedisQueueRepository implements QueueRepository {

    private final RedisScript<List> ENTER =
            RedisScript.of(new ClassPathResource("/redis/queue/enter.lua"), List.class);
    private final RedisScript<List> POSITION =
            RedisScript.of(new ClassPathResource("/redis/queue/position.lua"), List.class);
    private final RedisScript<List> ADMIT =
            RedisScript.of(new ClassPathResource("/redis/queue/admit.lua"), List.class);
    private final RedisScript<List> ISSUE_TOKEN =
            RedisScript.of(new ClassPathResource("/redis/queue/issue-token.lua"), List.class);

    private final StringRedisTemplate stringRedisTemplate;


    @Override
    public EnterOutcome enter(long scheduleId,
                              long userId, long openAtEpochMilli, long startsAtEpochMilli, long tokenTtlMillis, long keyExpireAtEpochSecond) {
        List r = call(() -> stringRedisTemplate.execute(ENTER, QueueKeys.of(scheduleId).enterKeys(),
                String.valueOf(userId), String.valueOf(openAtEpochMilli), String.valueOf(startsAtEpochMilli),
                String.valueOf(tokenTtlMillis), String.valueOf(keyExpireAtEpochSecond)));
        EnterOutcome.Type type = EnterOutcome.Type.valueOf((String) r.get(0));
        if(r.size() == 1) {
            return new EnterOutcome(type, null, null);
        }
        return new EnterOutcome(type, (Long) r.get(1), (Long) r.get(2));
    }

    @Override
    public PositionOutcome position(long scheduleId, long userId, long tokenTtlMillis) {
        List r = call(() -> stringRedisTemplate.execute(POSITION, QueueKeys.of(scheduleId).positionKeys(),
                String.valueOf(userId), String.valueOf(tokenTtlMillis)));
        PositionOutcome.Type type = PositionOutcome.Type.valueOf((String) r.get(0));
        if (r.size() == 1) {
            return new PositionOutcome(type, null, null);
        }
        return new PositionOutcome(type, (Long) r.get(1), (Long) r.get(2));
    }

    @Override
    public void admit(long scheduleId, int admitPerSecond, long tokenTtlMillis, int cleanupLimit, long keyExpireAtEpochSecond) {
        call(() -> stringRedisTemplate.execute(ADMIT, QueueKeys.of(scheduleId).admitKeys(),
                String.valueOf(admitPerSecond), String.valueOf(tokenTtlMillis),
                String.valueOf(cleanupLimit), String.valueOf(keyExpireAtEpochSecond)));
    }

    @Override
    public IssueTokenOutcome issueToken(long scheduleId, long userId, long tokenTtlMillis, long keyExpireAtEpochSecond) {
        List r = call(() -> stringRedisTemplate.execute(ISSUE_TOKEN, QueueKeys.of(scheduleId).issueTokenKeys(),
                String.valueOf(userId), String.valueOf(tokenTtlMillis), String.valueOf(keyExpireAtEpochSecond)));
        IssueTokenOutcome.Type type = IssueTokenOutcome.Type.valueOf((String) r.get(0));
        if (r.size() == 1) {
            return new IssueTokenOutcome(type, null);
        }
        return new IssueTokenOutcome(type, (Long) r.get(1));
    }


    private <T> T call(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (QueryTimeoutException | RedisConnectionFailureException e) {
            throw new QueueStorageUnavailableException(e);
        }
    }
}
