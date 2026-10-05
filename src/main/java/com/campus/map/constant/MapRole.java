package com.campus.map.constant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MapRole {

    VIEWER(1),
    EDITOR(2),
    OWNER(3);

    private final int level;

    public static MapRole fromCode(String code) {
        return MapRole.valueOf(code);
    }

    public boolean atLeast(MapRole required) {
        return this.level >= required.level;
    }
}
