package com.campus.activity.mapper;

import com.campus.activity.dto.ActivityStopItem;
import com.campus.activity.entity.ActivityStop;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ActivityStopMapper {

    int insert(ActivityStop stop);

    int deleteByActivityId(@Param("activityId") Long activityId);

    List<ActivityStopItem> selectByActivityId(@Param("activityId") Long activityId);

    List<ActivityStopItem> selectByActivityIds(@Param("activityIds") List<Long> activityIds);
}
