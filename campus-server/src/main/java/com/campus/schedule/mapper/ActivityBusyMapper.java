package com.campus.schedule.mapper;

import com.campus.schedule.dto.BusyEntryDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 活动侧的忙碌来源查询（个人日程之外的第二个来源；两者联合后由求解器合并区间） */
@Mapper
public interface ActivityBusyMapper {

    /** 用户已报名（含创建）且进行中的活动，时间与窗口相交的部分 */
    List<BusyEntryDto> selectActivityBusy(@Param("userId") Long userId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);
}
