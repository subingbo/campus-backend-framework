package com.campus.activity.controller;

import com.campus.activity.dto.ActivityResponse;
import com.campus.activity.dto.CreateActivityRequest;
import com.campus.activity.service.ActivityService;
import com.campus.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "活动")
@RestController
@RequestMapping("/api/activities")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;

    @Operation(summary = "创建活动")
    @PostMapping
    public Result<ActivityResponse> create(@Valid @RequestBody CreateActivityRequest request) {
        return Result.success(activityService.create(request));
    }

    @Operation(summary = "活动详情")
    @GetMapping("/{id}")
    public Result<ActivityResponse> get(@PathVariable Long id) {
        return Result.success(activityService.get(id));
    }

    @Operation(summary = "报名")
    @PostMapping("/{id}/join")
    public Result<ActivityResponse> join(@PathVariable Long id) {
        return Result.success(activityService.join(id));
    }

    @Operation(summary = "取消报名")
    @DeleteMapping("/{id}/join")
    public Result<ActivityResponse> leave(@PathVariable Long id) {
        return Result.success(activityService.leave(id));
    }
}
