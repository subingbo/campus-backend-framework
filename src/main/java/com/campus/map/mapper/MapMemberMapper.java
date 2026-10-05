package com.campus.map.mapper;

import com.campus.map.dto.MapMemberResponse;
import com.campus.map.entity.MapMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MapMemberMapper {

    int insert(MapMember member);

    MapMember selectByMapAndUser(@Param("mapId") Long mapId, @Param("userId") Long userId);

    List<MapMemberResponse> selectByMapId(@Param("mapId") Long mapId);

    int updateRole(@Param("mapId") Long mapId, @Param("userId") Long userId, @Param("role") String role);

    int deleteByMapAndUser(@Param("mapId") Long mapId, @Param("userId") Long userId);
}
