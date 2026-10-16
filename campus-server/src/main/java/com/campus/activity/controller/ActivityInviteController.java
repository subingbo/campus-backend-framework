package com.campus.activity.controller;

import com.campus.activity.dto.ActivityInviteItem;
import com.campus.activity.service.ActivityService;
import com.campus.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "活动召集")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class ActivityInviteController {

    private final ActivityService activityService;

    @Operation(summary = "向指定用户发出召集邀请（仅创建者）")
    @PostMapping("/activities/{id}/invites")
    public Result<ActivityInviteItem> invite(@org.springframework.web.bind.annotation.PathVariable("id") Long activityId,
                                             @Valid @RequestBody InviteRequest request) {
        return Result.success(activityService.invite(activityId, request.getUserId()));
    }

    @Operation(summary = "我收到的待响应邀请")
    @GetMapping("/activity-invites/pending")
    public Result<List<ActivityInviteItem>> pending() {
        return Result.success(activityService.pendingInvites());
    }

    @Operation(summary = "接受邀请")
    @PostMapping("/activity-invites/{inviteId}/accept")
    public Result<ActivityInviteItem> accept(@PathVariable Long inviteId) {
        return Result.success(activityService.respondInvite(inviteId, true));
    }

    @Operation(summary = "拒绝邀请")
    @PostMapping("/activity-invites/{inviteId}/decline")
    public Result<ActivityInviteItem> decline(@PathVariable Long inviteId) {
        return Result.success(activityService.respondInvite(inviteId, false));
    }

    @Data
    public static class InviteRequest {
        @NotNull
        @Min(1)
        private Long userId;
    }
}
