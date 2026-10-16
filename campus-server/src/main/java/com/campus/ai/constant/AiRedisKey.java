package com.campus.ai.constant;

public final class AiRedisKey {

    private AiRedisKey() {
    }

    /** 同起止点同交通方式的路线缓存（TTL 10 分钟，省高德配额） */
    public static String route(String mode, double fromLng, double fromLat, double toLng, double toLat) {
        return "ai:route:" + mode + ":" + coord(fromLng) + "," + coord(fromLat)
                + ":" + coord(toLng) + "," + coord(toLat);
    }

    private static String coord(double value) {
        return String.format("%.6f", value);
    }
}
