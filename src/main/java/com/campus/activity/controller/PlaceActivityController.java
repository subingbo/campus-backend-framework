package com.campus.activity.controller;

import com.campus.activity.dto.ActivityResponse;
import com.campus.activity.service.ActivityService;
import com.campus.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "地点活动")
@RestController
@RequestMapping("/api/places/{placeId}/activities")
@RequiredArgsConstructor
public class PlaceActivityController {

    private final ActivityService activityService;

    @Operation(summary = "地点活动列表")
    @GetMapping
    public Result<List<ActivityResponse>> list(@PathVariable Long placeId) {
        return Result.success(activityService.listByPlace(placeId));
    }
}
