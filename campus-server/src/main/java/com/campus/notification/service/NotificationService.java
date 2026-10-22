package com.campus.notification.service;

import com.campus.activity.mapper.ActivityInviteMapper;
import com.campus.common.exception.BusinessException;
import com.campus.common.result.PageResult;
import com.campus.common.result.ResultCode;
import com.campus.common.tx.AfterCommit;
import com.campus.notification.dto.NotificationResponse;
import com.campus.notification.dto.NotificationSummary;
import com.campus.notification.entity.Notification;
import com.campus.notification.mapper.NotificationMapper;
import com.campus.social.mapper.FriendRequestMapper;
import com.campus.social.service.ConversationService;
import com.campus.ws.WsSessionRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一通知收件箱。
 *
 * <p>幂等：单条 INSERT + 唯一键；只有 created=true 才注册 afterCommit WS 推送。
 * 业务数据与通知行在同一事务提交；回滚时通知行一起回滚且不会推送。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final WsSessionRegistry wsSessionRegistry;
    private final ConversationService conversationService;
    private final FriendRequestMapper friendRequestMapper;
    private final ActivityInviteMapper activityInviteMapper;
    private final ObjectMapper objectMapper;

    public record NotificationCreateResult(Long notificationId, boolean created) {
    }

    @Transactional
    public NotificationCreateResult create(Long userId, String type, String title, String content,
                                           String targetType, Long targetId,
                                           Map<String, Object> payload, String dedupeKey) {
        if (userId == null || !StringUtils.hasText(type) || !StringUtils.hasText(title)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "通知参数不完整");
        }
        String normalizedKey = StringUtils.hasText(dedupeKey) ? dedupeKey.trim() : null;
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setTargetType(targetType);
        notification.setTargetId(targetId);
        notification.setPayloadJson(serializePayload(NotificationPayloadPolicy.sanitize(payload)));
        notification.setDedupeKey(normalizedKey);
        try {
            notificationMapper.insert(notification);
        } catch (DuplicateKeyException ex) {
            Notification existing = normalizedKey == null ? null
                    : notificationMapper.selectByDedupe(userId, normalizedKey);
            return new NotificationCreateResult(existing == null ? null : existing.getId(), false);
        }
        // 只有真正新建才推送；重复命中不重复广播
        AfterCommit.run(() -> push(notification));
        return new NotificationCreateResult(notification.getId(), true);
    }

    @Transactional
    public int createForUsers(Collection<Long> userIds, String type, String title, String content,
                              String targetType, Long targetId, Map<String, Object> payload,
                              String dedupeKey) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        int created = 0;
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            if (create(userId, type, title, content, targetType, targetId, payload, dedupeKey).created()) {
                created++;
            }
        }
        return created;
    }

    public PageResult<NotificationResponse> list(Long userId, boolean unreadOnly, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        long total = notificationMapper.countByUser(userId, unreadOnly);
        List<NotificationResponse> rows = notificationMapper
                .selectByUser(userId, unreadOnly, (safePage - 1) * safeSize, safeSize)
                .stream().map(this::toResponse).toList();
        return PageResult.of(rows, safePage, safeSize, total);
    }

    public long unreadCount(Long userId) {
        return notificationMapper.countUnread(userId);
    }

    public void markRead(Long userId, Long notificationId) {
        Notification notification = notificationMapper.selectById(notificationId);
        if (notification == null || !notification.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "通知不存在");
        }
        notificationMapper.markRead(notificationId, userId, LocalDateTime.now());
    }

    public void markAllRead(Long userId) {
        notificationMapper.markAllRead(userId, LocalDateTime.now());
    }

    /**
     * 角标：total 只算“未读通知 + 有未读的会话数”。
     * 好友申请/活动邀请各自只做 tab 待办角标，不再重复加进 total。
     */
    public NotificationSummary summary(Long userId) {
        long unreadNotifications = notificationMapper.countUnread(userId);
        long unreadChatConversations = conversationService.listMine().stream()
                .filter(conversation -> conversation.getUnread() > 0)
                .count();
        long pendingFriendRequests = friendRequestMapper.countReceivedPending(userId);
        long pendingActivityInvites = activityInviteMapper.countPendingByInvitee(userId);
        NotificationSummary summary = new NotificationSummary();
        summary.setUnreadNotifications(unreadNotifications);
        summary.setUnreadChatConversations(unreadChatConversations);
        summary.setPendingFriendRequests(pendingFriendRequests);
        summary.setPendingActivityInvites(pendingActivityInvites);
        summary.setTotal(unreadNotifications + unreadChatConversations);
        return summary;
    }

    private void push(Notification notification) {
        NotificationResponse response = toResponse(notification);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "NOTIFICATION");
        frame.put("notification", response);
        wsSessionRegistry.sendToUser(notification.getUserId(), frame);
    }

    private String serializePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            log.warn("通知 payload 序列化失败：{}", ex.toString());
            return null;
        }
    }

    private NotificationResponse toResponse(Notification notification) {
        NotificationResponse response = new NotificationResponse();
        response.setId(notification.getId());
        response.setType(notification.getType());
        response.setTitle(notification.getTitle());
        response.setContent(notification.getContent());
        response.setTargetType(notification.getTargetType());
        response.setTargetId(notification.getTargetId());
        response.setRead(notification.getReadAt() != null);
        response.setReadAt(notification.getReadAt());
        response.setCreatedAt(notification.getCreatedAt());
        response.setPayload(parsePayload(notification.getPayloadJson()));
        return response;
    }

    private Map<String, Object> parsePayload(String payloadJson) {
        if (!StringUtils.hasText(payloadJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return null;
        }
    }
}
