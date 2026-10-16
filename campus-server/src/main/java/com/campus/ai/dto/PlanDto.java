package com.campus.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 规划草稿（大模型 submit_plan 工具入参 / plan_json 存储格式 / 前端展示格式，三者同一结构） */
@Data
public class PlanDto {

    private String title;
    /** MEETING / COMPETITION / OTHER；缺省 OTHER */
    private String category;
    private String summary;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 缺省 20 */
    private Integer maxMembers;
    /** 参考的共享地图（可空，前端展示来源用） */
    private Long referenceMapId;
    private List<PlanStop> stops = new ArrayList<>();

    // ---------- P1.5 时空协同扩展（全部可空，向后兼容旧草稿） ----------

    /** 群体规划的目标群（群聊 @AI 时由后端盖章写入，非模型可指定） */
    private Long groupId;
    /** 方案实际使用的共建地图集合（证明确实使用了共建地图） */
    private List<Long> sourceMapIds;
    /** 选用的地点集合（提交时按 sourceMapIds 关联核实） */
    private List<Long> selectedPlaceIds;
    /** 忙闲覆盖（userId + status: KNOWN/UNKNOWN，来自 plan_itinerary 结果） */
    private List<PlanCoverage> availabilityCoverage;
    /** 站点间交通段摘要 */
    private List<PlanRouteSegment> routeSegments;
    /** 总交通耗时（分钟） */
    private Integer totalTravelMinutes;
    /** 共建地图快照指纹（保存前重算比对，防规划期间地图/地点变化） */
    private String planningSourceVersion;
    /** 约束状态：FEASIBLE / TENTATIVE（存在未授权忙闲成员） */
    private String constraintStatus;
    /** 推荐理由（≤500 字） */
    private String reason;

    // ---------- H 复审强化（全部可空，向后兼容） ----------

    /**
     * 谱系活动：已保存活动经修订后再保存时走活动更新接口（不重建）。
     * 仅 revise 在“已保存会话且真实内容变化（且未显式 asNewDraft）”时由后端写入；
     * LLM submit_plan 与客户端经 revise 提交的草案一律被服务端覆写/清空，无法伪造谱系。
     */
    private Long lineageActivityId;
    /** 路线数据置信度：AMAP（高德真实返回）/ ESTIMATED（退化估算，不能宣称路线已验证） */
    private String routeConfidence;

    @Data
    public static class PlanCoverage {
        private Long userId;
        /** KNOWN = 已授权忙闲；UNKNOWN = 未授权，方案暂定 */
        private String status;
    }

    @Data
    public static class PlanRouteSegment {
        /** 1-based 站点序号 */
        private Integer fromSeq;
        private Integer toSeq;
        private String transportMode;
        private Double distanceMeters;
        private Integer durationMinutes;
    }
}
