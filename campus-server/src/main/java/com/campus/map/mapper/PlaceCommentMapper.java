package com.campus.map.mapper;

import com.campus.map.dto.PlaceCommentResponse;
import com.campus.map.entity.PlaceComment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PlaceCommentMapper {

    int insert(PlaceComment comment);

    List<PlaceCommentResponse> selectByMapAndPlace(@Param("mapId") Long mapId, @Param("placeId") Long placeId);
}
