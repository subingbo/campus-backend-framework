package com.campus.ai.travel;

import com.campus.ai.constant.AiRedisKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 路线结果缓存：相同 mode+起止点 10 分钟内只查一次高德，省配额也保证规划与展示用同一份数据。
 * 仿 PlaceDetailCache：读失败即忽略，写失败不影响主流程。
 * 计数器（hits/misses，进程级累计）供 AI_RUN 观测做差分：命中=直接读到缓存，未命中=查缓存时不存在。
 */
@Slf4j
@Component
public class RouteCache {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();

    public RouteCache(StringRedisTemplate stringRedisTemplate,
                      @Qualifier("redisObjectMapper") ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public long hitCount() {
        return hitCount.get();
    }

    public long missCount() {
        return missCount.get();
    }

    public RouteInfo get(String cacheKey) {
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        if (json == null || json.isBlank()) {
            missCount.incrementAndGet();
            return null;
        }
        try {
            RouteInfo info = objectMapper.readValue(json, RouteInfo.class);
            hitCount.incrementAndGet();
            return info;
        } catch (Exception ex) {
            log.warn("Ignore broken route cache {}", cacheKey, ex);
            stringRedisTemplate.delete(cacheKey);
            return null;
        }
    }

    public void put(String cacheKey, RouteInfo info) {
        try {
            stringRedisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(info), TTL);
        } catch (Exception ex) {
            log.warn("Skip route cache write {}", cacheKey, ex);
        }
    }
}
