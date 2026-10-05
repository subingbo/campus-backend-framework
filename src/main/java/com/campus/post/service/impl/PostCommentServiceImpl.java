package com.campus.post.service.impl;

import com.campus.common.exception.BusinessException;
import com.campus.common.result.PageResult;
import com.campus.common.result.ResultCode;
import com.campus.common.util.PageUtils;
import com.campus.place.cache.PlaceDetailCache;
import com.campus.post.dto.CommentResponse;
import com.campus.post.dto.CreateCommentRequest;
import com.campus.post.entity.Post;
import com.campus.post.entity.PostComment;
import com.campus.post.mapper.PostCommentMapper;
import com.campus.post.mapper.PostMapper;
import com.campus.post.service.PostCommentService;
import com.campus.security.user.SecurityUtils;
import com.campus.user.entity.User;
import com.campus.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostCommentServiceImpl implements PostCommentService {

    private final PostMapper postMapper;
    private final PostCommentMapper postCommentMapper;
    private final UserMapper userMapper;
    private final PlaceDetailCache placeDetailCache;

    @Override
    @Transactional
    public CommentResponse createComment(Long postId, CreateCommentRequest request) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getStatus() == null || post.getStatus() != 1) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "动态不存在");
        }

        Long parentId = request.getParentId();
        if (parentId != null) {
            PostComment parent = postCommentMapper.selectById(parentId);
            if (parent == null || !parent.getPostId().equals(postId)) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "父评论无效");
            }
            if (parent.getParentId() != null) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "仅支持两级评论");
            }
        }

        PostComment comment = new PostComment();
        comment.setPostId(postId);
        comment.setUserId(SecurityUtils.getUserId());
        comment.setParentId(parentId);
        comment.setContent(request.getContent());
        postCommentMapper.insert(comment);
        placeDetailCache.evict(post.getPlaceId());
        PostComment saved = postCommentMapper.selectById(comment.getId());

        User user = userMapper.selectById(comment.getUserId());
        CommentResponse response = new CommentResponse();
        response.setId(comment.getId());
        response.setUserId(comment.getUserId());
        response.setAuthorNickname(user != null ? user.getNickname() : null);
        response.setAuthorAvatar(user != null ? user.getAvatar() : null);
        response.setContent(comment.getContent());
        response.setCreatedAt(saved != null ? saved.getCreatedAt() : comment.getCreatedAt());
        response.setParentId(parentId);
        return response;
    }

    @Override
    public PageResult<CommentResponse> listComments(Long postId, Integer page, Integer size) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getStatus() == null || post.getStatus() != 1) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "动态不存在");
        }
        int p = PageUtils.normalizePage(page);
        int s = PageUtils.normalizeSize(size);
        int offset = PageUtils.offset(p, s);
        List<CommentResponse> tops = postCommentMapper.selectTopLevelByPostId(postId, offset, s);
        long total = postCommentMapper.countTopLevelByPostId(postId);

        if (!tops.isEmpty()) {
            List<Long> parentIds = tops.stream().map(CommentResponse::getId).collect(Collectors.toList());
            List<CommentResponse> replies = postCommentMapper.selectRepliesByParentIds(parentIds);
            Map<Long, List<CommentResponse>> grouped = replies.stream()
                    .collect(Collectors.groupingBy(CommentResponse::getParentId));
            for (CommentResponse top : tops) {
                top.setReplies(grouped.getOrDefault(top.getId(), new ArrayList<>()));
            }
        }
        return PageResult.of(tops, p, s, total);
    }

    @Override
    @Transactional
    public void deleteComment(Long commentId) {
        PostComment comment = postCommentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "评论不存在");
        }
        Post post = postMapper.selectById(comment.getPostId());
        postCommentMapper.deleteWithReplies(commentId);
        if (post != null) {
            placeDetailCache.evict(post.getPlaceId());
        }
    }
}
