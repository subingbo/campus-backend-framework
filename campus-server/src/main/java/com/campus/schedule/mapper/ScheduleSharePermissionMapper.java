package com.campus.schedule.mapper;

import com.campus.schedule.dto.ScheduleShareOption;
import com.campus.schedule.entity.ScheduleSharePermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ScheduleSharePermissionMapper {

    /** 授权或更新等级；幂等（owner+target 唯一键） */
    int upsert(@Param("ownerUserId") Long ownerUserId,
               @Param("targetType") String targetType,
               @Param("targetId") Long targetId,
               @Param("permissionLevel") String permissionLevel);

    /** owner 是否对 target 授予指定等级（FREE_BUSY） */
    int existsGrant(@Param("ownerUserId") Long ownerUserId,
                    @Param("targetType") String targetType,
                    @Param("targetId") Long targetId,
                    @Param("permissionLevel") String permissionLevel);

    int delete(@Param("ownerUserId") Long ownerUserId,
               @Param("targetType") String targetType,
               @Param("targetId") Long targetId);

    ScheduleSharePermission selectByOwnerAndTarget(@Param("ownerUserId") Long ownerUserId,
                                                   @Param("targetType") String targetType,
                                                   @Param("targetId") Long targetId);

    /** 当前用户加入的群地图 + 是否已授予 FREE_BUSY */
    List<ScheduleShareOption> selectGroupOptions(@Param("userId") Long userId);
}
