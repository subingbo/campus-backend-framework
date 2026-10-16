package com.campus.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * P0.5 地点完整可授权上下文：地点基础信息 + 所在共建地图 + 每张地图的备注（保留 mapId 对应关系）。
 * notes 仅包含当前授权范围内的地图投影，绝不混合其他群体的备注。
 */
@Data
public class GroupPlaceContext {

    private Long placeId;
    private String name;
    private String description;
    private String address;
    private Long categoryId;
    private String categoryName;
    private Double longitude;
    private Double latitude;

    /** 该地点所在的（授权范围内）共建地图 */
    private List<Long> mapIds = new ArrayList<>();

    /** 每张地图下的备注聚合摘要，content 为该图下备注原文（最新在前） */
    private List<Note> notes = new ArrayList<>();

    /** 来源标签：GROUP_MAP（群语境）或 PERSONAL_MAP（个人语境） */
    private String source;

    @Data
    public static class Note {
        private Long mapId;
        private String content;

        public Note(Long mapId, String content) {
            this.mapId = mapId;
            this.content = content;
        }
    }
}
