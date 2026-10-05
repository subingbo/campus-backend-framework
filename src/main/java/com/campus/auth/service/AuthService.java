package com.campus.auth.service;

import com.campus.auth.dto.LoginRequest;
import com.campus.auth.dto.RegisterRequest;
import com.campus.auth.dto.TokenResponse;
import com.campus.common.exception.BusinessException;
import com.campus.common.ratelimit.RateLimiter;
import com.campus.common.result.ResultCode;
import com.campus.security.jwt.JwtProperties;
import com.campus.security.jwt.JwtTokenProvider;
import com.campus.security.user.LoginUser;
import com.campus.user.entity.User;
import com.campus.user.service.UserService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final TokenRedisService tokenRedisService;
    private final RateLimiter rateLimiter;

    public void register(RegisterRequest request) {
        userService.createUser(request.getUsername(), request.getPassword(), request.getNickname(), "USER");
    }

    public TokenResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        String clientIp = resolveClientIp(httpRequest);
        rateLimiter.check("auth:login:" + clientIp, 10, Duration.ofMinutes(1));

        User user = userService.getByUsername(request.getUsername());
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "账号已禁用");
        }
        return issueTokens(user.getId(), user.getUsername());
    }

    public TokenResponse refresh(String refreshToken) {
        Claims claims = jwtTokenProvider.parseToken(refreshToken);
        if (!jwtTokenProvider.isRefreshToken(claims)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "Token 无效");
        }
        String tokenId = jwtTokenProvider.getTokenId(claims);
        Long userId = jwtTokenProvider.getUserId(claims);
        Long storedUserId = tokenRedisService.getRefreshTokenUserId(tokenId);
        if (storedUserId == null || !storedUserId.equals(userId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        tokenRedisService.deleteRefreshToken(tokenId);
        String username = jwtTokenProvider.getUsername(claims);
        LoginUser loginUser = userService.loadLoginUser(userId);
        tokenRedisService.cacheLoginUser(loginUser, jwtProperties.getAccessTokenExpiration());
        return issueTokens(userId, username);
    }

    public void logout(String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Claims claims = jwtTokenProvider.parseToken(accessToken);
        if (jwtTokenProvider.isRefreshToken(claims)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "Token 无效");
        }
        String tokenId = jwtTokenProvider.getTokenId(claims);
        tokenRedisService.deleteRefreshToken(tokenId);
        tokenRedisService.blacklistToken(tokenId, jwtTokenProvider.getRemainingSeconds(claims));
        Long userId = jwtTokenProvider.getUserId(claims);
        tokenRedisService.evictLoginUser(userId);
    }

    private TokenResponse issueTokens(Long userId, String username) {
        String tokenId = jwtTokenProvider.newTokenId();
        String accessToken = jwtTokenProvider.generateAccessToken(userId, username, tokenId);
        String refreshToken = jwtTokenProvider.generateRefreshToken(userId, username, tokenId);
        tokenRedisService.saveRefreshToken(tokenId, userId, jwtProperties.getRefreshTokenExpiration());
        LoginUser loginUser = userService.loadLoginUser(userId);
        tokenRedisService.cacheLoginUser(loginUser, jwtProperties.getAccessTokenExpiration());
        return new TokenResponse(accessToken, refreshToken, jwtProperties.getAccessTokenExpiration());
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
