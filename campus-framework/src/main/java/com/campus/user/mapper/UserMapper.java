package com.campus.user.mapper;

import com.campus.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMapper {

    User selectByUsername(@Param("username") String username);

    User selectById(@Param("id") Long id);

    int insert(User user);

    int updateById(User user);

    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    Long selectRoleIdByCode(@Param("code") String code);

    List<User> selectPage(@Param("offset") int offset, @Param("limit") int limit);

    long countAll();
}
