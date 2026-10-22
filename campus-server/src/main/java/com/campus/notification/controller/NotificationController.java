package com.campus.notification.controller;

import com.campus.common.result.PageResult;
import com.campus.common.result.Result;
import com.campus.notification.dto.NotificationResponse;
import com.campus.notification.dto.NotificationSummary;
import com.campus.notification.service.NotificationService;
import com.campus.security.user.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "消息中心")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "我的通知列表")
    @GetMapping
    public Result<PageResult<NotificationResponse>> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return Result.success(notificationService.list(
                SecurityUtils.getUserId(), unreadOnly, page, size));
    }

    @Operation(summary = "消息中心角标汇总")
    @GetMapping("/summary")
    public Result<NotificationSummary> summary() {
        return Result.success(notificationService.summary(SecurityUtils.getUserId()));
    }

    @Operation(summary = "标记单条已读")
    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(SecurityUtils.getUserId(), id);
        return Result.success(null);
    }

    @Operation(summary = "全部标记已读")
    @PostMapping("/read-all")
    public Result<Void> markAllRead() {
        notificationService.markAllRead(SecurityUtils.getUserId());
        return Result.success(null);
    }
}
