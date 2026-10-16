package com.campus.activity.constant;

public final class ActivityRedisKey {

    private ActivityRedisKey() {
    }

    /** 提醒已发出的防重标记：SETNX + TTL，多一分钟余量防边界漏推 */
    public static String reminded(Long activityId, long minutesLeft) {
        return "activity:reminded:" + activityId + ":" + minutesLeft;
    }
}
