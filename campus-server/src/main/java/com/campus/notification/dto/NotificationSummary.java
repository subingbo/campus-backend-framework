package com.campus.notification.dto;

import lombok.Data;

/**
 * 消息中心角标：total 只由“未读通知 + 有未读的会话数”组成。
 * 好友申请/活动邀请数量只用于各自 tab 的待办角标，避免与通知重复计数。
 */
@Data
public class NotificationSummary {

    private long unreadNotifications;
    private long unreadChatConversations;
    private long pendingFriendRequests;
    private long pendingActivityInvites;
    private long total;
}
