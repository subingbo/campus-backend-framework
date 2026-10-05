package com.campus.post.controller;

import com.campus.common.result.PageResult;
import com.campus.common.result.Result;
import com.campus.post.dto.PlaceFeedResponse;
import com.campus.post.dto.PostListItemResponse;
import com.campus.post.service.PostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "地点圈")
@RestController
@RequestMapping("/api/places/{placeId}")
@RequiredArgsConstructor
public class PlacePostController {

    private final PostService postService;

    @Operation(summary = "地点圈首页")
    @GetMapping("/feed")
    public Result<PlaceFeedResponse> feed(
            @PathVariable Long placeId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.success(postService.getPlaceFeed(placeId, page, size));
    }

    @Operation(summary = "地点动态列表")
    @GetMapping("/posts")
    public Result<PageResult<PostListItemResponse>> posts(
            @PathVariable Long placeId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.success(postService.listByPlace(placeId, page, size));
    }
}
