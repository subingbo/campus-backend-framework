package com.campus.ai.trace.dto;

import lombok.Data;

import java.util.List;

/** 详情页草稿版本摘要：由 plan/draft + plan/validate + plan/revise 事件聚合，供前端做版本 diff */
@Data
public class TraceDraftVersionView {

    private Long draftId;
    private Integer version;
    private Integer stopCount;
    private Integer totalTravelMinutes;
    private String routeConfidence;
    private String constraintStatus;
    private List<Long> selectedPlaceIds = List.of();
    private List<Long> sourceMapIds = List.of();
    /** SUBMIT 阶段校验结果优先；保存/修订阶段复核不覆盖 */
    private Boolean validatePassed;
    private String validatePhase;
    private List<String> ruleIds = List.of();
    private Boolean reviseChanged;
    private Integer revisedFromVersion;
}
