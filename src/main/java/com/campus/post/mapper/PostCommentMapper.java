package com.campus.post.mapper;

import com.campus.post.entity.PostComment;
import com.campus.post.dto.CommentResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PostCommentMapper {

    int insert(PostComment comment);

    PostComment selectById(@Param("id") Long id);

    List<CommentResponse> selectTopLevelByPostId(@Param("postId") Long postId,
                                                 @Param("offset") int offset,
                                                 @Param("limit") int limit);

    long countTopLevelByPostId(@Param("postId") Long postId);

    List<CommentResponse> selectRepliesByParentIds(@Param("parentIds") List<Long> parentIds);

    int deleteWithReplies(@Param("id") Long id);
}
