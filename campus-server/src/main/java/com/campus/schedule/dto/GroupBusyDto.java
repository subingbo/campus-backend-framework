package com.campus.schedule.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 群体忙闲视图：known=已授权（本人=完整；他人=仅时段无标题），unknownUserIds=未授权成员。
 * 未授权成员不能视为"有空"（计划 §8.2），方案只能标注为暂定。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupBusyDto {

    private List<BusyEntryDto> known = new ArrayList<>();
    private List<Long> unknownUserIds = new ArrayList<>();
}
