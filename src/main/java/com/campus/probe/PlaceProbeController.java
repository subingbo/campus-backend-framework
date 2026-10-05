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

@Tag(name = "地点探测")
@RestController
@RequestMapping("/api/places")
@RequiredArgsConstructor
public class PlaceProbeController {

    private final RateLimiter rateLimiter;

    @Operation(summary = "创建地点（权限与限流探测）")
    @PreAuthorize("hasAuthority('place:create')")
    @PostMapping
    public Result<Void> createPlace() {
        Long userId = SecurityUtils.getUserId();
        rateLimiter.check("place:create:" + userId, 5, Duration.ofMinutes(1));
        return Result.success();
    }
}
