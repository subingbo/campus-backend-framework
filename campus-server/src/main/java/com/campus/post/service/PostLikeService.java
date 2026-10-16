package com.campus.post.service;

import com.campus.post.dto.LikeStatusResponse;

public interface PostLikeService {

    LikeStatusResponse like(Long postId);

    LikeStatusResponse unlike(Long postId);

    LikeStatusResponse status(Long postId);
}
