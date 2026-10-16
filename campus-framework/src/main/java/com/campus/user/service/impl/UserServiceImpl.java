package com.campus.user.service.impl;

import com.campus.auth.service.TokenRedisService;
import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.campus.post.mapper.PostLikeMapper;
import com.campus.post.mapper.PostMapper;
import com.campus.security.user.LoginUser;
import com.campus.security.user.SecurityUtils;
import com.campus.user.dto.UpdateUserMeRequest;
import com.campus.user.dto.UserHomeResponse;
import com.campus.user.dto.UserProfileResponse;
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
    private final PostMapper postMapper;
    private final PostLikeMapper postLikeMapper;
    private final TokenRedisService tokenRedisService;

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

    @Override
    public UserProfileResponse getCurrentProfile() {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        User user = userMapper.selectById(loginUser.getId());
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        UserProfileResponse response = new UserProfileResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setAvatar(user.getAvatar());
        response.setCreatedAt(user.getCreatedAt());
        response.setRoles(loginUser.getRoles());
        response.setPermissions(loginUser.getPermissions());
        return response;
    }

    @Override
    public UserProfileResponse updateCurrentProfile(UpdateUserMeRequest request) {
        Long userId = SecurityUtils.getUserId();
        User patch = new User();
        patch.setId(userId);
        if (request.getNickname() != null) {
            patch.setNickname(request.getNickname());
        }
        if (request.getAvatar() != null) {
            patch.setAvatar(request.getAvatar());
        }
        userMapper.updateById(patch);
        return getCurrentProfile();
    }

    @Override
    public UserHomeResponse getUserHome(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        UserHomeResponse response = new UserHomeResponse();
        response.setId(user.getId());
        response.setNickname(user.getNickname());
        response.setAvatar(user.getAvatar());
        response.setCreatedAt(user.getCreatedAt());
        response.setPostCount(postMapper.countByUserId(userId));
        response.setLikeReceivedCount(postLikeMapper.countLikesReceivedByUser(userId));
        return response;
    }

    @Override
    public void updateStatus(Long userId, int status) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        List<String> roles = roleMapper.selectCodesByUserId(userId);
        if (status == 0 && roles.contains("ADMIN")) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不能封禁管理员");
        }
        User patch = new User();
        patch.setId(userId);
        patch.setStatus(status);
        userMapper.updateById(patch);
        tokenRedisService.evictLoginUser(userId);
    }
}
