package com.campus.activity.mapper;

import com.campus.activity.dto.ActivityResponse;
import com.campus.activity.entity.Activity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
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

    int updateById(Activity activity);

    int updateStatus(@Param("id") Long id, @Param("status") int status);

    /** 提醒调度：某时间点之前开始、状态正常、且开始时间不早于 now 的活动（即窗口内的） */
    List<Activity> selectStartingBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 我的日程：我创建的或我已报名的（含已取消活动的展示标记，供日程页分组） */
    List<ActivityResponse> selectMine(@Param("userId") Long userId,
                                      @Param("currentUserId") Long currentUserId,
                                      @Param("limit") int limit);
}
