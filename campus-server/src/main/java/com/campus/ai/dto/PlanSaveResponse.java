package com.campus.ai.dto;

import com.campus.activity.dto.ActivityResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 草稿保存结果；activity 为完整活动视图（含 stops） */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanSaveResponse {

    private Long activityId;
    private ActivityResponse activity;
}
