package com.campus.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 群成员可见的规划摘要（字段白名单）。
 *
 * <p>绝不直接序列化 PlanDto：不包含 AI 消息、geo_json、原始工具响应、系统提示词、
 * 私有地图地点详情、他人日程标题、planningSourceVersion、selectedPlaceIds。
 * 私有地图站点的 placeId/坐标会置空并标 locationVisible=false。</p>
 */
@Data
public class GroupPlanPublicView {

    private Long planId;
    private Long conversationId;
    private Long messageId;
    private Long planVersion;
    private Long askerId;
    private String askerNickname;
    /** 从 ai_conversation.saved_activity_id 动态读取，不是快照字段 */
    private Long savedActivityId;

    private String title;
    private String category;
    private String summary;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer maxMembers;
    private String constraintStatus;
    private String routeConfidence;
    private Integer totalTravelMinutes;
    private String reason;
    private List<String> warnings = new ArrayList<>();
    private List<StopView> stops = new ArrayList<>();
    private List<MemberView> members = new ArrayList<>();
    private List<ConflictView> conflicts = new ArrayList<>();
    private List<SegmentView> routeSegments = new ArrayList<>();

    @Data
    public static class StopView {
        private Integer seq;
        private String name;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private String transportMode;
        private Integer stayMinutes;
        private Long placeId;
        private Double longitude;
        private Double latitude;
        /** false 表示来自私人地图或来源不可核实，地点详情仅发起人可见 */
        private boolean locationVisible;
    }

    @Data
    public static class MemberView {
        private Long userId;
        private String nickname;
        private String role;
        private String status;
    }

    @Data
    public static class ConflictView {
        private Long userId;
        private String nickname;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private String severity;
        private Integer overlapMinutes;
        private String suggestion;
    }

    @Data
    public static class SegmentView {
        private Integer fromSeq;
        private Integer toSeq;
        private String transportMode;
        private Double distanceMeters;
        private Integer durationMinutes;
    }
}
