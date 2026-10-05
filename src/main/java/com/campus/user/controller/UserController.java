package com.campus.user.controller;

import com.campus.common.result.Result;
import com.campus.security.user.LoginUser;
import com.campus.security.user.SecurityUtils;
import com.campus.user.dto.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "用户")
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Operation(summary = "当前用户")
    @GetMapping("/me")
    public Result<UserProfileResponse> me() {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        UserProfileResponse response = new UserProfileResponse();
        response.setId(loginUser.getId());
        response.setUsername(loginUser.getUsername());
        response.setNickname(loginUser.getNickname());
        response.setRoles(loginUser.getRoles());
        response.setPermissions(loginUser.getPermissions());
        return Result.success(response);
    }
}
