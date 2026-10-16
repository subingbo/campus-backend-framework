package com.campus.schedule.mapper;

import com.campus.schedule.entity.ScheduleSharePermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
