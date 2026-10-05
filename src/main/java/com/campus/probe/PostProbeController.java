package com.campus.probe;

import com.campus.common.ratelimit.RateLimiter;
import com.campus.common.result.Result;
import com.campus.security.user.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@Tag(name = "帖子探测")
@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostProbeController {

    private final RateLimiter rateLimiter;

    @Operation(summary = "创建帖子（权限与限流探测）")
    @PreAuthorize("hasAuthority('post:create')")
    @PostMapping
    public Result<Void> createPost() {
        Long userId = SecurityUtils.getUserId();
        rateLimiter.check("post:create:" + userId, 10, Duration.ofMinutes(1));
        return Result.success();
    }
}
