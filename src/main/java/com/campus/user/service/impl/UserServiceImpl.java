package com.campus.user.service.impl;

import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.campus.security.user.LoginUser;
import com.campus.user.entity.User;
import com.campus.user.mapper.PermissionMapper;
import com.campus.user.mapper.RoleMapper;
import com.campus.user.mapper.UserMapper;
import com.campus.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public User getById(Long id) {
        return userMapper.selectById(id);
    }

    @Override
    public User getByUsername(String username) {
        return userMapper.selectByUsername(username);
    }

    @Override
    public LoginUser loadLoginUser(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "账号已禁用");
        }
        List<String> roles = roleMapper.selectCodesByUserId(id);
        List<String> permissions = permissionMapper.selectCodesByUserId(id);
        return LoginUser.from(user, roles, permissions);
    }

    @Override
    @Transactional
    public User createUser(String username, String password, String nickname, String roleCode) {
        if (userMapper.selectByUsername(username) != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "用户名已存在");
        }
        Long roleId = userMapper.selectRoleIdByCode(roleCode);
        if (roleId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "角色不存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname != null ? nickname : username);
        user.setStatus(1);
        userMapper.insert(user);
        userMapper.insertUserRole(user.getId(), roleId);
        user.setPassword(null);
        return user;
    }

    @Override
    public void updateUser(User user) {
        userMapper.updateById(user);
    }
}
