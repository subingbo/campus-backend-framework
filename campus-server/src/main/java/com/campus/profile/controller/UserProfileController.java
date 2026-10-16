package com.campus.profile.controller;

import com.campus.common.result.Result;
import com.campus.profile.dto.UpdateUserMeRequest;
import com.campus.profile.dto.UserHomeResponse;
import com.campus.profile.dto.UserProfileResponse;
import com.campus.profile.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "用户")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;

    @Operation(summary = "当前用户")
    @GetMapping("/me")
    public Result<UserProfileResponse> me() {
        return Result.success(userProfileService.getCurrentProfile());
    }

    @Operation(summary = "修改个人资料")
    @PutMapping("/me")
    public Result<UserProfileResponse> updateMe(@Valid @RequestBody UpdateUserMeRequest request) {
        return Result.success(userProfileService.updateCurrentProfile(request));
    }

    @Operation(summary = "用户主页")
    @GetMapping("/{userId}")
    public Result<UserHomeResponse> userHome(@PathVariable Long userId) {
        return Result.success(userProfileService.getUserHome(userId));
    }
}
