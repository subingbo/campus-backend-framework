package com.campus.activity.service.impl;

import com.campus.activity.dto.ActivityResponse;
import com.campus.activity.dto.CreateActivityRequest;
import com.campus.activity.entity.Activity;
import com.campus.activity.mapper.ActivityMapper;
import com.campus.activity.mapper.ActivityMemberMapper;
import com.campus.activity.service.ActivityService;
import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.campus.place.service.PlaceService;
import com.campus.security.user.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ActivityServiceImpl implements ActivityService {

    private static final int PLACE_LIST_LIMIT = 50;

    private final ActivityMapper activityMapper;
    private final ActivityMemberMapper activityMemberMapper;
    private final PlaceService placeService;

    @Override
    @Transactional
    public ActivityResponse create(CreateActivityRequest request) {
        placeService.requireActivePlace(request.getPlaceId());
        if (!request.getEndTime().isAfter(request.getStartTime())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "结束时间必须晚于开始时间");
        }
        Long userId = SecurityUtils.getUserId();
        Activity activity = new Activity();
        activity.setPlaceId(request.getPlaceId());
        activity.setCreatorId(userId);
        activity.setTitle(request.getTitle());
        activity.setDescription(request.getDescription());
        activity.setStartTime(request.getStartTime());
        activity.setEndTime(request.getEndTime());
        activity.setMaxMembers(request.getMaxMembers());
        activity.setStatus(1);
        activityMapper.insert(activity);
        activityMemberMapper.insert(activity.getId(), userId);
        return requireView(activity.getId(), userId);
    }

    @Override
    public ActivityResponse get(Long id) {
        return requireView(id, SecurityUtils.getUserId());
    }

    @Override
    public List<ActivityResponse> listByPlace(Long placeId) {
        placeService.requireActivePlace(placeId);
        return withPhase(activityMapper.selectByPlaceId(placeId, SecurityUtils.getUserId(), PLACE_LIST_LIMIT));
    }

    @Override
    public List<ActivityResponse> upcomingByPlace(Long placeId, int limit) {
        return withPhase(activityMapper.selectUpcomingByPlaceId(placeId, SecurityUtils.getUserId(), limit));
    }

    @Override
    @Transactional
    public ActivityResponse join(Long id) {
        Activity activity = requireActive(id);
        if (!activity.getEndTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "活动已结束");
        }
        Long userId = SecurityUtils.getUserId();
        if (activityMemberMapper.exists(id, userId) > 0) {
            return requireView(id, userId);
        }
        if (activityMemberMapper.countByActivityId(id) >= activity.getMaxMembers()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "活动名额已满");
        }
        try {
            activityMemberMapper.insert(id, userId);
        } catch (DuplicateKeyException ignored) {
            // concurrent join
        }
        return requireView(id, userId);
    }

    @Override
    @Transactional
    public ActivityResponse leave(Long id) {
        requireActive(id);
        activityMemberMapper.delete(id, SecurityUtils.getUserId());
        return requireView(id, SecurityUtils.getUserId());
    }

    private Activity requireActive(Long id) {
        Activity activity = activityMapper.selectById(id);
        if (activity == null || activity.getStatus() == null || activity.getStatus() != 1) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "活动不存在");
        }
        return activity;
    }

    private ActivityResponse requireView(Long id, Long userId) {
        ActivityResponse response = activityMapper.selectViewById(id, userId);
        if (response == null || response.getStatus() == null || response.getStatus() != 1) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "活动不存在");
        }
        fillPhase(response);
        return response;
    }

    private List<ActivityResponse> withPhase(List<ActivityResponse> rows) {
        for (ActivityResponse row : rows) {
            fillPhase(row);
        }
        return rows;
    }

    private void fillPhase(ActivityResponse response) {
        if (response.getStatus() != null && response.getStatus() == 0) {
            response.setPhase("CANCELLED");
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (response.getStartTime() != null && now.isBefore(response.getStartTime())) {
            response.setPhase("OPEN");
        } else if (response.getEndTime() != null && now.isAfter(response.getEndTime())) {
            response.setPhase("ENDED");
        } else {
            response.setPhase("ONGOING");
        }
    }
}
