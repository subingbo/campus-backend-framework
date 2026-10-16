package com.campus.map.mapper;

import com.campus.map.dto.PlaceCommentResponse;
import com.campus.map.entity.PlaceComment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface PlaceCommentMapper {

    int insert(PlaceComment comment);

    List<PlaceCommentResponse> selectByMapAndPlace(@Param("mapId") Long mapId, @Param("placeId") Long placeId);

    /** 指纹用备注聚合：每（map, place）的最新备注 id 与条数，加/删备注都会改变 planningSourceVersion */
    List<Map<String, Object>> selectDigestByMaps(@Param("mapIds") List<Long> mapIds);
}
