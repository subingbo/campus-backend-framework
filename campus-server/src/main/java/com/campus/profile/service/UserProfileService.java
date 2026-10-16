package com.campus.profile.service;

import com.campus.profile.dto.UpdateUserMeRequest;
import com.campus.profile.dto.UserHomeResponse;
import com.campus.profile.dto.UserProfileResponse;

public interface UserProfileService {

    UserProfileResponse getCurrentProfile();

    UserProfileResponse updateCurrentProfile(UpdateUserMeRequest request);

    UserHomeResponse getUserHome(Long userId);

    void updateStatus(Long userId, int status);
}
