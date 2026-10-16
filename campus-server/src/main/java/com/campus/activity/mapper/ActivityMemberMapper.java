package com.campus.activity.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ActivityMemberMapper {

    int insert(@Param("activityId") Long activityId, @Param("userId") Long userId);

    int delete(@Param("activityId") Long activityId, @Param("userId") Long userId);

    int exists(@Param("activityId") Long activityId, @Param("userId") Long userId);

    long countByActivityId(@Param("activityId") Long activityId);
}
