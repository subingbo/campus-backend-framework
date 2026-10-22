package com.campus.activity.mapper;

import com.campus.activity.dto.ActivityInviteItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ActivityInviteMapper {

    int insert(@Param("activityId") Long activityId,
               @Param("inviterId") Long inviterId,
               @Param("inviteeId") Long inviteeId);

    ActivityInviteItem selectById(@Param("id") Long id);

    /** 只锁邀请行（不 join activity），保证锁顺序为 invite -> activity */
    ActivityInviteItem selectForUpdate(@Param("id") Long id);

    /** 按活动+被邀请人定位一条邀请（响应 accept/decline 用） */
    ActivityInviteItem selectByActivityAndInvitee(@Param("activityId") Long activityId,
                                                  @Param("inviteeId") Long inviteeId);

    /** 我收到的待响应邀请（日程页"收到的邀请"） */
    List<ActivityInviteItem> selectPendingByInvitee(@Param("inviteeId") Long inviteeId,
                                                    @Param("limit") int limit);

    /** 某活动的全部邀请状态（仅创建者使用） */
    List<ActivityInviteItem> selectByActivityId(@Param("activityId") Long activityId,
                                               @Param("limit") int limit);

    int respond(@Param("id") Long id,
                @Param("status") int status,
                @Param("time") LocalDateTime time);

    int exists(@Param("activityId") Long activityId, @Param("inviteeId") Long inviteeId);
}
