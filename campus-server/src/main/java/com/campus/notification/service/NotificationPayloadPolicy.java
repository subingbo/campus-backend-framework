package com.campus.notification.service;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 通知 payload 只允许“点开后路由所需的最小字段”。
 *
 * <p>业务代码不应把签到码、日程标题/备注、坐标或工具响应塞进通知。
 * 这里做集中白名单并在服务端清洗，避免新增调用点绕过隐私约束。</p>
 */
@Slf4j
public final class NotificationPayloadPolicy {

    static final Set<String> ALLOWED_KEYS = Set.of(
            "activityId",
            "inviteId",
            "fromUserId",
            "userId",
            "mapId",
            "peerUserId",
            "conversationId"
    );

    private static final int MAX_VALUE_LENGTH = 64;

    private NotificationPayloadPolicy() {
    }

    public static Map<String, Object> sanitize(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!ALLOWED_KEYS.contains(key)) {
                log.warn("通知 payload 丢弃非白名单字段：{}", key);
                continue;
            }
            if (!isScalar(value)) {
                log.warn("通知 payload 丢弃非标量字段：{}", key);
                continue;
            }
            if (value instanceof String text && text.length() > MAX_VALUE_LENGTH) {
                log.warn("通知 payload 丢弃超长字段：{}", key);
                continue;
            }
            safe.put(key, value);
        }
        return safe.isEmpty() ? null : Collections.unmodifiableMap(safe);
    }

    private static boolean isScalar(Object value) {
        return value instanceof Number || value instanceof Boolean
                || value instanceof CharSequence;
    }
}
