package com.campus.ws;

import com.campus.common.constant.RedisKey;
import com.campus.social.mapper.FriendMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 会话注册表：userId → 多端会话（多标签页各占一条）。
 * 在线状态以本进程内存为准（单实例），同时写 Redis SET 供后续多实例演进；
 * 好友上线/下线时向其好友广播 PRESENCE。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsSessionRegistry {

    private final Map<Long, Set<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();
    private final StringRedisTemplate stringRedisTemplate;
    private final FriendMapper friendMapper;
    private final ObjectMapper objectMapper;

    /** 返回是否为该用户第一条会话（用于上下线判定） */
    public void register(Long userId, WebSocketSession session) {
        Set<WebSocketSession> set = sessionsByUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet());
        synchronized (set) {
            boolean first = set.isEmpty();
            set.add(session);
            if (first) {
                onPresenceChanged(userId, true);
            }
        }
    }

    public void unregister(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(WsHandshakeInterceptor.ATTR_USER_ID);
        if (userId == null) {
            return;
        }
        Set<WebSocketSession> set = sessionsByUser.get(userId);
        if (set == null) {
            return;
        }
        synchronized (set) {
            set.remove(session);
            if (set.isEmpty()) {
                sessionsByUser.remove(userId, set);
                onPresenceChanged(userId, false);
            }
        }
    }

    public boolean isOnline(Long userId) {
        Set<WebSocketSession> set = sessionsByUser.get(userId);
        return set != null && !set.isEmpty();
    }

    public void sendToUser(Long userId, Map<String, Object> frame) {
        Set<WebSocketSession> set = sessionsByUser.get(userId);
        if (set == null || set.isEmpty()) {
            return;
        }
        String payload = toJson(frame);
        if (payload == null) {
            return;
        }
        synchronized (set) {
            for (WebSocketSession session : set) {
                try {
                    if (session.isOpen()) {
                        synchronized (session) {
                            session.sendMessage(new TextMessage(payload));
                        }
                    }
                } catch (IOException ex) {
                    log.warn("WS 推送失败 userId={}：{}", userId, ex.getMessage());
                }
            }
        }
    }

    public void sendToUsers(Collection<Long> userIds, Map<String, Object> frame) {
        for (Long userId : userIds) {
            sendToUser(userId, frame);
        }
    }

    private void onPresenceChanged(Long userId, boolean online) {
        try {
            if (online) {
                stringRedisTemplate.opsForSet().add(RedisKey.socialOnline(), String.valueOf(userId));
            } else {
                stringRedisTemplate.opsForSet().remove(RedisKey.socialOnline(), String.valueOf(userId));
            }
        } catch (Exception ex) {
            log.warn("在线状态写入 Redis 失败 userId={}：{}", userId, ex.getMessage());
        }
        List<Long> friendIds = friendMapper.selectFriendIds(userId);
        if (!friendIds.isEmpty()) {
            sendToUsers(friendIds, Map.of(
                    "type", "PRESENCE",
                    "userId", userId,
                    "online", online));
        }
    }

    private String toJson(Map<String, Object> frame) {
        try {
            return objectMapper.writeValueAsString(frame);
        } catch (Exception ex) {
            log.warn("WS 帧序列化失败：{}", ex.getMessage());
            return null;
        }
    }
}
