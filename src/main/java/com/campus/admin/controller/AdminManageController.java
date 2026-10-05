package com.campus.admin.controller;

import com.campus.admin.dto.AdminPlaceItem;
import com.campus.admin.dto.AdminPostItem;
import com.campus.admin.dto.AdminUserItem;
import com.campus.admin.dto.UpdateUserStatusRequest;
import com.campus.admin.service.AdminManageService;
import com.campus.common.result.PageResult;
import com.campus.common.result.Result;
import com.campus.report.dto.HandleReportRequest;
import com.campus.report.dto.ReportResponse;
import com.campus.report.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理端")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminManageController {

    private final AdminManageService adminManageService;
    private final ReportService reportService;

    @Operation(summary = "用户列表")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/users")
    public Result<PageResult<AdminUserItem>> users(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.success(adminManageService.listUsers(page, size));
    }

    @Operation(summary = "修改用户状态")
    @PreAuthorize("hasAuthority('user:ban')")
    @PutMapping("/users/{id}/status")
    public Result<Void> updateUserStatus(@PathVariable Long id, @Valid @RequestBody UpdateUserStatusRequest request) {
        adminManageService.updateUserStatus(id, request.getStatus());
        return Result.success();
    }

    @Operation(summary = "地点列表")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/places")
    public Result<PageResult<AdminPlaceItem>> places(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.success(adminManageService.listPlaces(page, size));
    }

    @Operation(summary = "隐藏地点")
    @PreAuthorize("hasAuthority('place:delete')")
    @DeleteMapping("/places/{id}")
    public Result<Void> hidePlace(@PathVariable Long id) {
        adminManageService.hidePlace(id);
        return Result.success();
    }

    @Operation(summary = "动态列表")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/posts")
    public Result<PageResult<AdminPostItem>> posts(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.success(adminManageService.listPosts(page, size));
    }

    @Operation(summary = "隐藏动态")
    @PreAuthorize("hasAuthority('post:delete')")
    @DeleteMapping("/posts/{id}")
    public Result<Void> hidePost(@PathVariable Long id) {
        adminManageService.hidePost(id);
        return Result.success();
    }

    @Operation(summary = "举报列表")
    @PreAuthorize("hasAuthority('report:handle')")
    @GetMapping("/reports")
    public Result<PageResult<ReportResponse>> reports(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.success(reportService.list(status, page, size));
    }

    @Operation(summary = "处理举报")
    @PreAuthorize("hasAuthority('report:handle')")
    @PutMapping("/reports/{id}")
    public Result<ReportResponse> handleReport(@PathVariable Long id,
                                               @Valid @RequestBody HandleReportRequest request) {
        return Result.success(reportService.handle(id, request));
    }
}
