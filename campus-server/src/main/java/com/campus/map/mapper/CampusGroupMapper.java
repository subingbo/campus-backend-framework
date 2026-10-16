package com.campus.map.mapper;

import com.campus.map.entity.CampusGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CampusGroupMapper {

    int insert(CampusGroup group);

    CampusGroup selectById(@Param("id") Long id);

    CampusGroup selectByInviteCode(@Param("inviteCode") String inviteCode);

    int updateVisibility(@Param("id") Long id, @Param("visibility") String visibility);
}
