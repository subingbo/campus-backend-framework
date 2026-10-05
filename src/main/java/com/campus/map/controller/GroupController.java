package com.campus.map.controller;

import com.campus.common.result.Result;
import com.campus.map.dto.CreateGroupRequest;
import com.campus.map.dto.GroupCreateResponse;
import com.campus.map.dto.JoinGroupRequest;
import com.campus.map.dto.MapDetailResponse;
import com.campus.map.service.SharedMapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "群组")
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final SharedMapService sharedMapService;

    @Operation(summary = "创建群组与共享地图")
    @PostMapping
    public Result<GroupCreateResponse> create(@Valid @RequestBody CreateGroupRequest request) {
        return Result.success(sharedMapService.createGroup(request));
    }

    @Operation(summary = "邀请码加入群组")
    @PostMapping("/join")
    public Result<MapDetailResponse> join(@Valid @RequestBody JoinGroupRequest request) {
        return Result.success(sharedMapService.joinGroup(request));
    }
}
