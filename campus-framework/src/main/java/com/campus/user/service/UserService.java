package com.campus.user.service;

import com.campus.security.user.LoginUser;
import com.campus.user.entity.User;

public interface UserService {

    User getById(Long id);

    User getByUsername(String username);

    LoginUser loadLoginUser(Long id);

    User createUser(String username, String password, String nickname, String roleCode);

    void updateUser(User user);
}
