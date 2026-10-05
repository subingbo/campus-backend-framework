package com.campus.post.mapper;

import com.campus.post.entity.Post;
import com.campus.post.dto.PostListItemResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PostMapper {

    int insert(Post post);

    Post selectById(@Param("id") Long id);

    long countByUserId(@Param("userId") Long userId);

    long countByPlaceId(@Param("placeId") Long placeId);

    List<PostListItemResponse> selectPageByPlaceId(@Param("placeId") Long placeId,
                                                 @Param("currentUserId") Long currentUserId,
                                                 @Param("offset") int offset,
                                                 @Param("limit") int limit);

    long countPageByPlaceId(@Param("placeId") Long placeId);

    List<PostListItemResponse> selectLatestByPlaceId(@Param("placeId") Long placeId,
                                                     @Param("currentUserId") Long currentUserId,
                                                     @Param("limit") int limit);

    PostListItemResponse selectItemById(@Param("id") Long id, @Param("currentUserId") Long currentUserId);

    int updateStatus(@Param("id") Long id, @Param("status") int status);

    List<com.campus.admin.dto.AdminPostItem> selectAdminPage(@Param("offset") int offset, @Param("limit") int limit);

    long countAdmin();

    List<com.campus.post.dto.PlaceFeedResponse.MemberPreview> selectMemberPreview(@Param("placeId") Long placeId,
                                                                                  @Param("limit") int limit);
}
