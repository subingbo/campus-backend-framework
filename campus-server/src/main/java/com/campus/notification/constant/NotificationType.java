package com.campus.notification.constant;

/** 通知类型与跳转目标类型（避免各处字符串漂移） */
public final class NotificationType {

    public static final String FRIEND_REQUEST = "FRIEND_REQUEST";
    public static final String FRIEND_ACCEPTED = "FRIEND_ACCEPTED";
    public static final String ACTIVITY_INVITED = "ACTIVITY_INVITED";
    public static final String ACTIVITY_REMINDER = "ACTIVITY_REMINDER";
    public static final String ACTIVITY_CANCELLED = "ACTIVITY_CANCELLED";
    public static final String ACTIVITY_MEMBER_JOINED = "ACTIVITY_MEMBER_JOINED";

    public static final String TARGET_ACTIVITY = "ACTIVITY";
    public static final String TARGET_FRIEND_REQUEST = "FRIEND_REQUEST";
    public static final String TARGET_FRIEND = "FRIEND";
    public static final String TARGET_ACTIVITY_INVITE = "ACTIVITY_INVITE";
    public static final String TARGET_CONVERSATION = "CONVERSATION";
    public static final String TARGET_NONE = "NONE";

    private NotificationType() {
    }
}
