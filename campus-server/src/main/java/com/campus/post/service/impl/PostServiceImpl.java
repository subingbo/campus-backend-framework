package com.campus.post.service.impl;

import com.campus.activity.service.ActivityService;
import com.campus.common.exception.BusinessException;
import com.campus.common.result.PageResult;
import com.campus.common.result.ResultCode;
import com.campus.common.util.PageUtils;
import com.campus.place.cache.PlaceDetailCache;
import com.campus.place.service.PlaceService;
import com.campus.post.dto.CreatePostRequest;
import com.campus.post.dto.PlaceFeedResponse;
import com.campus.post.dto.PostListItemResponse;
import com.campus.post.entity.Post;
import com.campus.post.mapper.PostMapper;
import com.campus.post.service.PostService;
import com.campus.security.user.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private final PostMapper postMapper;
    private final PlaceService placeService;
    private final PlaceDetailCache placeDetailCache;
    private final ActivityService activityService;

    @Override
    @Transactional
    public PostListItemResponse createPost(CreatePostRequest request) {
        placeService.requireActivePlace(request.getPlaceId());
        Post post = new Post();
        post.setUserId(SecurityUtils.getUserId());
        post.setPlaceId(request.getPlaceId());
        post.setContent(request.getContent());
        post.setStatus(1);
        postMapper.insert(post);
        placeDetailCache.evict(request.getPlaceId());
        PostListItemResponse item = postMapper.selectItemById(post.getId(), SecurityUtils.getUserId());
        if (item == null) {
            throw new IllegalStateException("post not found after insert");
        }
        return item;
    }

    @Override
    public PageResult<PostListItemResponse> listByPlace(Long placeId, Integer page, Integer size) {
        placeService.requireActivePlace(placeId);
        int p = PageUtils.normalizePage(page);
        int s = PageUtils.normalizeSize(size);
        int offset = PageUtils.offset(p, s);
        Long userId = SecurityUtils.getUserId();
        List<PostListItemResponse> list = postMapper.selectPageByPlaceId(placeId, userId, offset, s);
        long total = postMapper.countPageByPlaceId(placeId);
        return PageResult.of(list, p, s, total);
    }

    @Override
    public PlaceFeedResponse getPlaceFeed(Long placeId, Integer page, Integer size) {
        PlaceFeedResponse feed = new PlaceFeedResponse();
        feed.setPlace(placeService.getDetail(placeId));
        feed.setPosts(listByPlace(placeId, page, size));
        feed.setMembers(postMapper.selectMemberPreview(placeId, 10));
        feed.setActivities(activityService.upcomingByPlace(placeId, 5));
        return feed;
    }

    @Override
    public void hidePost(Long id) {
        Post post = postMapper.selectById(id);
        if (post == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "动态不存在");
        }
        postMapper.updateStatus(id, 0);
        placeDetailCache.evict(post.getPlaceId());
    }
}
