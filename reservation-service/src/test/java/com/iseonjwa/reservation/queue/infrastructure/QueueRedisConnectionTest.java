package com.iseonjwa.reservation.queue.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataRedisTest
@ActiveProfiles("test")
class QueueRedisConnectionTest {

    @Autowired
    StringRedisTemplate redis;

    @Test
    void Redis에_값을_저장하고_조회한다() {
        String key = "test:queue:" + UUID.randomUUID();
        try {
            redis.opsForValue().set(key, "hello");

            assertEquals("hello", redis.opsForValue().get(key));
        } finally {
            redis.delete(key);
        }
    }
}