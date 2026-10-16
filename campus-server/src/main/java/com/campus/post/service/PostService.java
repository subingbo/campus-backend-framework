package com.campus.post.service;

import com.campus.common.result.PageResult;
import com.campus.post.dto.CreatePostRequest;
import com.campus.post.dto.PlaceFeedResponse;
import com.campus.post.dto.PostListItemResponse;

public interface PostService {

    PostListItemResponse createPost(CreatePostRequest request);

    PageResult<PostListItemResponse> listByPlace(Long placeId, Integer page, Integer size);

    PlaceFeedResponse getPlaceFeed(Long placeId, Integer page, Integer size);

    void hidePost(Long id);
}
