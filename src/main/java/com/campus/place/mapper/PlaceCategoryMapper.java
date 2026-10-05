package com.campus.place.mapper;

import com.campus.place.entity.PlaceCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PlaceCategoryMapper {

    List<PlaceCategory> selectActiveAll();

    PlaceCategory selectById(@Param("id") Long id);
}
