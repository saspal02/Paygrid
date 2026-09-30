package com.saswat.paygrid.common_lib.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.rate-limit.method", havingValue = "sliding")
public class SlidingWindowRateLimiter implements RateLimiter{

    private final StringRedisTemplate redis;

    @Override
    public RateLimitResult check(String key, int maxRequestAllowed, long windowSeconds) {

        long nowMs = System.currentTimeMillis();
        long floorMs = nowMs - windowSeconds*1000;

        String redisKey = "ratelimit:sliding:"+key;

        var zset = redis.opsForZSet();
        zset.removeRangeByScore(redisKey, Double.NEGATIVE_INFINITY, floorMs);

        Long count = zset.zCard(redisKey);
        long current = count != null ? count: 0;

        if (current >= maxRequestAllowed) {
            var oldest = zset.rangeWithScores(redisKey, 0, 0);
            int retryAfter = 1;

            if ((oldest != null && !oldest.isEmpty())) {
                Double oldestScore = oldest.iterator().next().getScore();
                if (oldestScore != null ) {
                    long windowExpireMs = oldestScore.longValue() + windowSeconds*1000;
                    retryAfter = (int) Math.ceil((windowExpireMs - nowMs)/ 1000.0);


                }
            }
            return RateLimitResult.denied(retryAfter);

        }

        zset.add(redisKey, UUID.randomUUID().toString(), nowMs);
        redis.expire(redisKey, Duration.ofSeconds(windowSeconds+1));
        return RateLimitResult.allowed((int) (maxRequestAllowed - current - 1));
    }
}
