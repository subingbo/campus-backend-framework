package com.campus.activity.mapper;

import com.campus.activity.dto.ActivityResponse;
import com.campus.activity.entity.Activity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ActivityMapper {

    int insert(Activity activity);

    Activity selectById(@Param("id") Long id);

    List<ActivityResponse> selectByPlaceId(@Param("placeId") Long placeId,
                                           @Param("currentUserId") Long currentUserId,
                                           @Param("limit") int limit);

    List<ActivityResponse> selectUpcomingByPlaceId(@Param("placeId") Long placeId,
                                                   @Param("currentUserId") Long currentUserId,
                                                   @Param("limit") int limit);

    ActivityResponse selectViewById(@Param("id") Long id, @Param("currentUserId") Long currentUserId);

    int updateStatus(@Param("id") Long id, @Param("status") int status);
}
