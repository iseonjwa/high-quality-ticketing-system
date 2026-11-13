package com.iseonjwa.reservation.queue.infrastructure;

import com.iseonjwa.reservation.queue.domain.*;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@Repository
public class RedisQueueRepository implements QueueRepository {

    private final RedisScript<List> ENTER =
            RedisScript.of(new ClassPathResource("/redis/queue/enter.lua"), List.class);
    private final RedisScript<List> POSITION =
            RedisScript.of(new ClassPathResource("/redis/queue/position.lua"), List.class);
    private final RedisScript<List> ADMIT =
            RedisScript.of(new ClassPathResource("/redis/queue/admit.lua"), List.class);
    private final RedisScript<List> ISSUE_TOKEN =
            RedisScript.of(new ClassPathResource("/redis/queue/issue-token.lua"), List.class);
    private final RedisScript<Void> DISCARD_TOKEN =
            RedisScript.of(new ClassPathResource("/redis/queue/discard-token.lua"));

    private final StringRedisTemplate stringRedisTemplate;
    private final CircuitBreaker circuitBreaker;
    private final Duration waitDurationInOpenState;

    public RedisQueueRepository(StringRedisTemplate stringRedisTemplate, CircuitBreaker circuitBreaker, QueueRedisCircuitBreakerProperties queueRedisCircuitBreakerProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.circuitBreaker = circuitBreaker;
        this.waitDurationInOpenState = queueRedisCircuitBreakerProperties.waitDurationInOpenState();
    }


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

    @Override
    public Optional<Long> issuedAt(long scheduleId, long userId) {
        Double score = call(() -> stringRedisTemplate.opsForZSet()
                .score(QueueKeys.of(scheduleId).issuedKey(), String.valueOf(userId)));
        return Optional.ofNullable(score).map(Double::longValue);
    }

    @Override
    public void discardToken(long scheduleId, long userId, long issuedAtEpochMillis) {
        call(() -> stringRedisTemplate.execute(DISCARD_TOKEN, QueueKeys.of(scheduleId).discardEntryTokenKeys(),
                String.valueOf(userId), String.valueOf(issuedAtEpochMillis)));
    }

    // Redis 장애가 발생했을 때 따로 예외처리를 하기 위한 메서드
    private <T> T call(Supplier<T> supplier) {
        try {
            return circuitBreaker.executeSupplier(supplier); // 호출 시간과 성공·실패를 차단기에 기록하고, 원래 예외를 그대로 다시 던짐
        } catch (CallNotPermittedException e) { // 차단기가 호출을 허락하지 않을 때(=열려있을 때) 터지는 예외
            throw new QueueStorageUnavailableException(waitDurationInOpenState, e);
        } catch (QueryTimeoutException | RedisConnectionFailureException e) {
            throw new QueueStorageUnavailableException(e);
        }
    }
}
