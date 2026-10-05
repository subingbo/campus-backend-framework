package com.campus.user.mapper;

import com.campus.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {

    User selectByUsername(@Param("username") String username);

    User selectById(@Param("id") Long id);

    int insert(User user);

    int updateById(User user);

    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    Long selectRoleIdByCode(@Param("code") String code);
}
