package com.campus.common.util;

public final class SqlLikeUtils {

    private SqlLikeUtils() {
    }

    public static String escapeLike(String keyword) {
        if (keyword == null) {
            return "";
        }
        return keyword.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
