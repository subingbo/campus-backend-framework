package com.campus.map.mapper;

import com.campus.map.dto.MapListItemResponse;
import com.campus.map.entity.SharedMap;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SharedMapMapper {

    int insert(SharedMap map);

    SharedMap selectById(@Param("id") Long id);

    SharedMap selectByGroupId(@Param("groupId") Long groupId);

    List<MapListItemResponse> selectByUserId(@Param("userId") Long userId);

    int countMembers(@Param("mapId") Long mapId);

    int countPlaces(@Param("mapId") Long mapId);
}
