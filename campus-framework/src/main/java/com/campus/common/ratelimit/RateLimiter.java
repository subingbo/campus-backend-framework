package com.campus.common.ratelimit;

import com.campus.common.constant.RedisKey;
import com.campus.common.exception.RateLimitException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;

@Component
public class RateLimiter {

    private static final String LUA = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            if current > tonumber(ARGV[1]) then
                return 0
            end
            return 1
            """;

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> rateLimitScript;

    public RateLimiter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.rateLimitScript = new DefaultRedisScript<>();
        this.rateLimitScript.setScriptText(LUA);
        this.rateLimitScript.setResultType(Long.class);
    }

    public void check(String bizKey, int limit, Duration window) {
        String key = RedisKey.rate(bizKey);
        long windowMillis = window.toMillis();
        Long allowed = stringRedisTemplate.execute(
                rateLimitScript,
                Collections.singletonList(key),
                String.valueOf(limit),
                String.valueOf(windowMillis)
        );
        if (allowed == null || allowed == 0L) {
            throw new RateLimitException();
        }
    }
}
