package com.campus.post.controller;

import com.campus.common.result.Result;
import com.campus.post.dto.LikeStatusResponse;
import com.campus.post.service.PostLikeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "点赞")
@RestController
@RequestMapping("/api/posts/{postId}/like")
@RequiredArgsConstructor
public class PostLikeController {

    private final PostLikeService postLikeService;

    @Operation(summary = "点赞")
    @PostMapping
    public Result<LikeStatusResponse> like(@PathVariable Long postId) {
        return Result.success(postLikeService.like(postId));
    }

    @Operation(summary = "取消点赞")
    @DeleteMapping
    public Result<LikeStatusResponse> unlike(@PathVariable Long postId) {
        return Result.success(postLikeService.unlike(postId));
    }

    @Operation(summary = "点赞状态")
    @GetMapping
    public Result<LikeStatusResponse> status(@PathVariable Long postId) {
        return Result.success(postLikeService.status(postId));
    }
}
