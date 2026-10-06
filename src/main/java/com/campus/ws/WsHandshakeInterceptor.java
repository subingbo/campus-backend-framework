package com.campus.ws;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import com.campus.auth.service.TokenRedisService;
import com.campus.security.jwt.JwtTokenProvider;
import io.jsonwebtoken.Claims;

import java.util.Map;

/**
 * 握手阶段鉴权：浏览器 WebSocket 无法携带 Authorization 头，
 * 约定通过 /ws?token=<accessToken> 传入，复用 REST 同一套 JWT 校验
 * （拒绝 refresh 型 token、拒绝黑名单 token）。
 */
@Component
@RequiredArgsConstructor
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "wsUserId";

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenRedisService tokenRedisService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = resolveToken(request);
        if (token == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            Claims claims = jwtTokenProvider.parseToken(token);
            if (jwtTokenProvider.isRefreshToken(claims)
                    || tokenRedisService.isBlacklisted(jwtTokenProvider.getTokenId(claims))) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            attributes.put(ATTR_USER_ID, jwtTokenProvider.getUserId(claims));
            return true;
        } catch (Exception ex) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    private String resolveToken(ServerHttpRequest request) {
        String query = request.getURI().getQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "token".equals(pair.substring(0, eq))) {
                String value = pair.substring(eq + 1);
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }
}
