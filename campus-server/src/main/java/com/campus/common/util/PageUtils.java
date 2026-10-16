package com.campus.common.util;

public final class PageUtils {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;

    private PageUtils() {
    }

    public static int normalizePage(Integer page) {
        if (page == null || page < 1) {
            return 1;
        }
        return page;
    }

    public static int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    public static int offset(int page, int size) {
        return (page - 1) * size;
    }
}
