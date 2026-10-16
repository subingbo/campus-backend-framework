package com.campus.schedule.mapper;

import com.campus.schedule.entity.PersonalSchedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PersonalScheduleMapper {

    int insert(PersonalSchedule schedule);

    PersonalSchedule selectById(@Param("id") Long id);

    /** 与窗口 [from,to) 相交的本人的日程 */
    List<PersonalSchedule> selectOverlap(@Param("userId") Long userId,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);

    int update(PersonalSchedule schedule);

    int deleteByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);
}
