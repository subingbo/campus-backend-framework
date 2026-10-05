package com.campus.post.controller;

import com.campus.common.result.PageResult;
import com.campus.common.result.Result;
import com.campus.post.dto.CommentResponse;
import com.campus.post.dto.CreateCommentRequest;
import com.campus.post.service.PostCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "评论")
@RestController
@RequestMapping("/api/posts/{postId}/comments")
@RequiredArgsConstructor
public class PostCommentController {

    private final PostCommentService postCommentService;

    @Operation(summary = "发布评论")
    @PreAuthorize("hasAuthority('comment:create')")
    @PostMapping
    public Result<CommentResponse> create(
            @PathVariable Long postId,
            @Valid @RequestBody CreateCommentRequest request) {
        return Result.success(postCommentService.createComment(postId, request));
    }

    @Operation(summary = "评论列表")
    @GetMapping
    public Result<PageResult<CommentResponse>> list(
            @PathVariable Long postId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.success(postCommentService.listComments(postId, page, size));
    }
}
