package com.campus.place.cache;

import com.campus.place.constant.PlaceRedisKey;
import com.campus.place.dto.PlaceDetailResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
public class PlaceDetailCache {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public PlaceDetailCache(StringRedisTemplate stringRedisTemplate,
                            @Qualifier("redisObjectMapper") ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public PlaceDetailResponse get(Long placeId) {
        String json = stringRedisTemplate.opsForValue().get(PlaceRedisKey.detail(placeId));
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PlaceDetailResponse.class);
        } catch (Exception ex) {
            log.warn("Ignore broken place cache {}", placeId, ex);
            evict(placeId);
            return null;
        }
    }

    public void put(PlaceDetailResponse detail) {
        if (detail == null || detail.getId() == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    PlaceRedisKey.detail(detail.getId()),
                    objectMapper.writeValueAsString(detail),
                    TTL
            );
        } catch (Exception ex) {
            log.warn("Skip place cache write {}", detail.getId(), ex);
        }
    }

    public void evict(Long placeId) {
        if (placeId == null) {
            return;
        }
        stringRedisTemplate.delete(PlaceRedisKey.detail(placeId));
    }
}
