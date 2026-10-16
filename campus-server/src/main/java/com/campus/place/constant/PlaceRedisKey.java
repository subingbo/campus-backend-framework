package com.campus.place.constant;

public final class PlaceRedisKey {

    private PlaceRedisKey() {
    }

    public static String detail(Long placeId) {
        return "place:detail:" + placeId;
    }
}
