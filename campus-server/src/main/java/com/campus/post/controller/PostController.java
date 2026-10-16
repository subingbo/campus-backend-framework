package com.campus.post.controller;

import com.campus.common.ratelimit.RateLimiter;
import com.campus.common.result.Result;
import com.campus.post.dto.CreatePostRequest;
import com.campus.post.dto.PostListItemResponse;
import com.campus.post.service.PostService;
import com.campus.security.user.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@Tag(name = "动态")
@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;
    private final RateLimiter rateLimiter;

    @Operation(summary = "发布动态")
    @PreAuthorize("hasAuthority('post:create')")
    @PostMapping
    public Result<PostListItemResponse> create(@Valid @RequestBody CreatePostRequest request) {
        Long userId = SecurityUtils.getUserId();
        rateLimiter.check("post:create:" + userId, 10, Duration.ofMinutes(1));
        return Result.success(postService.createPost(request));
    }
}
