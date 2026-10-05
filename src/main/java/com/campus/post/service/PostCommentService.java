package com.campus.post.service;

import com.campus.common.result.PageResult;
import com.campus.post.dto.CommentResponse;
import com.campus.post.dto.CreateCommentRequest;

public interface PostCommentService {

    CommentResponse createComment(Long postId, CreateCommentRequest request);

    PageResult<CommentResponse> listComments(Long postId, Integer page, Integer size);

    void deleteComment(Long commentId);
}
