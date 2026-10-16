package com.campus.profile.service.impl;

import com.campus.auth.service.TokenRedisService;
import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.campus.post.mapper.PostLikeMapper;
import com.campus.post.mapper.PostMapper;
import com.campus.profile.dto.UpdateUserMeRequest;
import com.campus.profile.dto.UserHomeResponse;
import com.campus.profile.dto.UserProfileResponse;
import com.campus.profile.service.UserProfileService;
import com.campus.security.user.LoginUser;
import com.campus.security.user.SecurityUtils;
import com.campus.user.entity.User;
import com.campus.user.mapper.RoleMapper;
import com.campus.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PostMapper postMapper;
    private final PostLikeMapper postLikeMapper;
    private final TokenRedisService tokenRedisService;

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
