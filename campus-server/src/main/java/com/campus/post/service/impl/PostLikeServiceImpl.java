package com.campus.post.service.impl;

import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.campus.place.cache.PlaceDetailCache;
import com.campus.post.dto.LikeStatusResponse;
import com.campus.post.entity.Post;
import com.campus.post.mapper.PostLikeMapper;
import com.campus.post.mapper.PostMapper;
import com.campus.post.service.PostLikeService;
import com.campus.security.user.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostLikeServiceImpl implements PostLikeService {

    private final PostMapper postMapper;
    private final PostLikeMapper postLikeMapper;
    private final PlaceDetailCache placeDetailCache;

    @Override
    @Transactional
    public LikeStatusResponse like(Long postId) {
        Post post = requireActivePost(postId);
        Long userId = SecurityUtils.getUserId();
        try {
            postLikeMapper.insert(postId, userId);
        } catch (DuplicateKeyException ignored) {
            // idempotent
        }
        placeDetailCache.evict(post.getPlaceId());
        return status(postId);
    }

    @Override
    @Transactional
    public LikeStatusResponse unlike(Long postId) {
        Post post = requireActivePost(postId);
        postLikeMapper.delete(postId, SecurityUtils.getUserId());
        placeDetailCache.evict(post.getPlaceId());
        return status(postId);
    }

    @Override
    public LikeStatusResponse status(Long postId) {
        requireActivePost(postId);
        Long userId = SecurityUtils.getUserId();
        LikeStatusResponse response = new LikeStatusResponse();
        response.setLiked(postLikeMapper.exists(postId, userId) > 0);
        response.setLikeCount(postLikeMapper.countByPostId(postId));
        return response;
    }

    private Post requireActivePost(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getStatus() == null || post.getStatus() != 1) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "动态不存在");
        }
        return post;
    }
}
