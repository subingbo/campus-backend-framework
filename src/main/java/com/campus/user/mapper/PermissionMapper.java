package com.campus.user.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PermissionMapper {

    List<String> selectCodesByUserId(@Param("userId") Long userId);
}
