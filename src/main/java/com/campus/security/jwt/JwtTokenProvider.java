package com.campus.security.jwt;

import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_TOKEN_ID = "tokenId";
    public static final String CLAIM_TYPE = "type";
    public static final String TYPE_REFRESH = "refresh";

    private final JwtProperties jwtProperties;

    public String generateAccessToken(Long userId, String username, String tokenId) {
        return buildToken(userId, username, tokenId, null, jwtProperties.getAccessTokenExpiration());
    }

    public String generateRefreshToken(Long userId, String username, String tokenId) {
        return buildToken(userId, username, tokenId, TYPE_REFRESH, jwtProperties.getRefreshTokenExpiration());
    }

    public String newTokenId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException ex) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "Token 已过期");
        } catch (JwtException ex) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "Token 无效");
        }
    }

    public void validateToken(String token) {
        parseToken(token);
    }

    public Long getUserId(Claims claims) {
        Object userId = claims.get(CLAIM_USER_ID);
        if (userId instanceof Number number) {
            return number.longValue();
        }
        throw new BusinessException(ResultCode.UNAUTHORIZED);
    }

    public String getTokenId(Claims claims) {
        return claims.get(CLAIM_TOKEN_ID, String.class);
    }

    public String getUsername(Claims claims) {
        return claims.get(CLAIM_USERNAME, String.class);
    }

    public boolean isRefreshToken(Claims claims) {
        return TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public boolean isExpired(Claims claims) {
        Date expiration = claims.getExpiration();
        return expiration != null && expiration.before(new Date());
    }

    public long getRemainingSeconds(Claims claims) {
        Date expiration = claims.getExpiration();
        if (expiration == null) {
            return 0;
        }
        long diff = expiration.getTime() - System.currentTimeMillis();
        return Math.max(diff / 1000, 0);
    }

    private String buildToken(Long userId, String username, String tokenId, String type, long expirationSeconds) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationSeconds * 1000);
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_TOKEN_ID, tokenId)
                .issuedAt(now)
                .expiration(expiry);
        if (type != null) {
            builder.claim(CLAIM_TYPE, type);
        }
        return builder.signWith(secretKey()).compact();
    }

    private SecretKey secretKey() {
        byte[] keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
