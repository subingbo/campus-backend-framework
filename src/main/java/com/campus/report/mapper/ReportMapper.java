package com.campus.report.mapper;

import com.campus.report.entity.Report;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReportMapper {

    int insert(Report report);

    Report selectById(@Param("id") Long id);

    List<Report> selectPage(@Param("status") Integer status,
                            @Param("offset") int offset,
                            @Param("limit") int limit);

    long count(@Param("status") Integer status);

    int updateHandled(Report report);
}
