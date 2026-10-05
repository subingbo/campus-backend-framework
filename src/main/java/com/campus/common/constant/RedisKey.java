package com.campus.common.constant;

public final class RedisKey {

    private RedisKey() {
    }

    public static String refreshToken(String tokenId) {
        return "refresh_token:" + tokenId;
    }

    public static String loginUser(Long userId) {
        return "login_user:" + userId;
    }

    public static String blacklist(String tokenId) {
        return "blacklist:" + tokenId;
    }

    public static String rate(String bizKey) {
        return "rate:" + bizKey;
    }

    public static String placeDetail(Long placeId) {
        return "place:detail:" + placeId;
    }
}
