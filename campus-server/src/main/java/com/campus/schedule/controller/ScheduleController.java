package com.campus.schedule.controller;

import com.campus.common.result.Result;
import com.campus.schedule.dto.BusyEntryDto;
import com.campus.schedule.dto.GroupBusyDto;
import com.campus.schedule.dto.SaveScheduleRequest;
import com.campus.schedule.dto.ScheduleShareOption;
import com.campus.schedule.dto.ShareScheduleRequest;
import com.campus.schedule.entity.PersonalSchedule;
import com.campus.schedule.service.ScheduleService;
import com.campus.security.user.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@Tag(name = "个人日程")
@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    @Operation(summary = "我的日程列表（可选区间，缺省未来 30 天）")
    @GetMapping
    public Result<List<PersonalSchedule>> mine(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return Result.success(scheduleService.listMine(SecurityUtils.getUserId(), from, to));
    }

    @Operation(summary = "新增个人日程")
    @PostMapping
    public Result<PersonalSchedule> create(@Valid @RequestBody SaveScheduleRequest request) {
        return Result.success(scheduleService.create(SecurityUtils.getUserId(), request));
    }

    @Operation(summary = "修改个人日程")
    @PutMapping("/{id}")
    public Result<PersonalSchedule> update(@PathVariable Long id, @Valid @RequestBody SaveScheduleRequest request) {
        return Result.success(scheduleService.update(SecurityUtils.getUserId(), id, request));
    }

    @Operation(summary = "删除个人日程")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        scheduleService.delete(SecurityUtils.getUserId(), id);
        return Result.success(null);
    }

    @Operation(summary = "我的多源忙闲（personal_schedule ∪ 已报名活动，含标题）")
    @GetMapping("/busy")
    public Result<List<BusyEntryDto>> busy(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return Result.success(scheduleService.myBusy(SecurityUtils.getUserId(), from, to));
    }

    @Operation(summary = "我的群忙闲授权选项（当前用户加入的群 + 是否已授权）")
    @GetMapping("/share")
    public Result<List<ScheduleShareOption>> shareOptions() {
        return Result.success(scheduleService.listGroupShareOptions(SecurityUtils.getUserId()));
    }

    @Operation(summary = "设置/撤销忙闲共享授权（FREE_BUSY 仅时段，不暴露内容）")
    @PutMapping("/share")
    public Result<Void> share(@Valid @RequestBody ShareScheduleRequest request) {
        scheduleService.setShare(SecurityUtils.getUserId(), request);
        return Result.success(null);
    }

    @Operation(summary = "群体忙闲（基于本人授权视图；未授权成员计入 unknown，不视为有空）")
    @GetMapping("/group-busy")
    public Result<GroupBusyDto> groupBusy(
            @RequestParam Long groupId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return Result.success(scheduleService.groupBusy(SecurityUtils.getUserId(), groupId, from, to));
    }
}
