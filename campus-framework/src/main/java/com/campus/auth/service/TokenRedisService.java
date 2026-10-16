package com.campus.auth.service;

import com.campus.common.constant.RedisKey;
import com.campus.security.user.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TokenRedisService {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisTemplate<String, Object> redisTemplate;

    public void saveRefreshToken(String tokenId, Long userId, long ttlSeconds) {
        stringRedisTemplate.opsForValue().set(
                RedisKey.refreshToken(tokenId),
                String.valueOf(userId),
                ttlSeconds,
                TimeUnit.SECONDS
        );
    }

    public Long getRefreshTokenUserId(String tokenId) {
        String value = stringRedisTemplate.opsForValue().get(RedisKey.refreshToken(tokenId));
        if (value == null) {
            return null;
        }
        return Long.parseLong(value);
    }

    public void deleteRefreshToken(String tokenId) {
        stringRedisTemplate.delete(RedisKey.refreshToken(tokenId));
    }

    public void cacheLoginUser(LoginUser loginUser, long ttlSeconds) {
        redisTemplate.opsForValue().set(
                RedisKey.loginUser(loginUser.getId()),
                loginUser,
                Duration.ofSeconds(ttlSeconds)
        );
    }

    public LoginUser getLoginUser(Long userId) {
        Object value = redisTemplate.opsForValue().get(RedisKey.loginUser(userId));
        if (value instanceof LoginUser loginUser) {
            return loginUser;
        }
        return null;
    }

    public void evictLoginUser(Long userId) {
        redisTemplate.delete(RedisKey.loginUser(userId));
    }

    public boolean isBlacklisted(String tokenId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKey.blacklist(tokenId)));
    }

    public void blacklistToken(String tokenId, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            return;
        }
        stringRedisTemplate.opsForValue().set(
                RedisKey.blacklist(tokenId),
                "1",
                ttlSeconds,
                TimeUnit.SECONDS
        );
    }
}
