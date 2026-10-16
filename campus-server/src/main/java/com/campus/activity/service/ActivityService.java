package com.campus.activity.service;

import com.campus.activity.dto.ActivityResponse;
import com.campus.activity.dto.CreateActivityRequest;

import java.util.List;

public interface ActivityService {

    ActivityResponse create(CreateActivityRequest request);

    ActivityResponse get(Long id);

    List<ActivityResponse> listByPlace(Long placeId);

    List<ActivityResponse> upcomingByPlace(Long placeId, int limit);

    ActivityResponse join(Long id);

    ActivityResponse leave(Long id);
}
