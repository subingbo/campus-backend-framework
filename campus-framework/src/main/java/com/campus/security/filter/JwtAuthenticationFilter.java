package com.campus.security.filter;

import com.campus.auth.service.TokenRedisService;
import com.campus.common.result.Result;
import com.campus.common.result.ResultCode;
import com.campus.security.jwt.JwtTokenProvider;
import com.campus.security.user.LoginUser;
import com.campus.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenRedisService tokenRedisService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (!StringUtils.hasText(token)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            Claims claims = jwtTokenProvider.parseToken(token);
            if (jwtTokenProvider.isRefreshToken(claims)) {
                writeUnauthorized(response, "Token 无效");
                return;
            }
            String tokenId = jwtTokenProvider.getTokenId(claims);
            if (tokenRedisService.isBlacklisted(tokenId)) {
                writeUnauthorized(response, ResultCode.UNAUTHORIZED.getMessage());
                return;
            }
            Long userId = jwtTokenProvider.getUserId(claims);
            LoginUser loginUser = tokenRedisService.getLoginUser(userId);
            if (loginUser == null) {
                loginUser = userService.loadLoginUser(userId);
                tokenRedisService.cacheLoginUser(loginUser, 3600);
            }
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (Exception ex) {
            SecurityContextHolder.clearContext();
            writeUnauthorized(response, ResultCode.UNAUTHORIZED.getMessage());
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Result.error(ResultCode.UNAUTHORIZED.getCode(), message));
    }
}
