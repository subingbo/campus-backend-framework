package com.campus.activity.mapper;

import com.campus.activity.dto.ActivityMemberItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ActivityMemberMapper {

    int insert(@Param("activityId") Long activityId, @Param("userId") Long userId);

    int delete(@Param("activityId") Long activityId, @Param("userId") Long userId);

    int exists(@Param("activityId") Long activityId, @Param("userId") Long userId);

    long countByActivityId(@Param("activityId") Long activityId);

    int checkin(@Param("activityId") Long activityId,
                @Param("userId") Long userId,
                @Param("time") LocalDateTime time);

    List<ActivityMemberItem> listByActivityId(@Param("activityId") Long activityId);

    List<Long> selectUserIdsByActivityId(@Param("activityId") Long activityId);
}
