package com.campus.social.constant;

public final class SocialRedisKey {

    private SocialRedisKey() {
    }

    public static String online() {
        return "social:online";
    }

    public static String unread(Long userId, Long conversationId) {
        return "social:unread:" + userId + ":" + conversationId;
    }
}
