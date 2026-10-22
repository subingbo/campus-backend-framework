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

    /** 仅未签到成员可取消报名；返回影响行数，0 表示未报名或已签到 */
    int deleteIfNotCheckedIn(@Param("activityId") Long activityId, @Param("userId") Long userId);

    /** 查询签到状态；null 表示成员行不存在 */
    Integer selectCheckedIn(@Param("activityId") Long activityId, @Param("userId") Long userId);

    int exists(@Param("activityId") Long activityId, @Param("userId") Long userId);

    long countByActivityId(@Param("activityId") Long activityId);

    int checkin(@Param("activityId") Long activityId,
                @Param("userId") Long userId,
                @Param("time") LocalDateTime time);

    List<ActivityMemberItem> listByActivityId(@Param("activityId") Long activityId);

    List<Long> selectUserIdsByActivityId(@Param("activityId") Long activityId);
}
