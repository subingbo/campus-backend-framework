package com.campus.ai.service;

import com.campus.ai.dto.GroupPlanHandoff;
import com.campus.ai.dto.GroupPlanPublicView;
import com.campus.ai.dto.PlanDto;
import com.campus.ai.dto.PlanStop;
import com.campus.ai.entity.AiConversation;
import com.campus.ai.entity.GroupAgentPlan;
import com.campus.ai.mapper.AiConversationMapper;
import com.campus.ai.mapper.GroupAgentPlanMapper;
import com.campus.ai.plan.PlanSource;
import com.campus.user.entity.User;
import com.campus.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 群规划摘要：只返回 {@link GroupPlanPublicView} 白名单字段。
 * 私有地图站点不返回 placeId/坐标；实时起点直接过滤；保存状态动态读 ai_conversation。
 */
@Slf4j
@Service
public class GroupPlanQueryService {

    private final GroupAgentPlanMapper planMapper;
    private final AiConversationMapper conversationMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    public GroupPlanQueryService(GroupAgentPlanMapper planMapper,
                                 AiConversationMapper conversationMapper,
                                 UserMapper userMapper,
                                 @Qualifier("redisObjectMapper") ObjectMapper objectMapper) {
        this.planMapper = planMapper;
        this.conversationMapper = conversationMapper;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public List<GroupPlanPublicView> list(Long conversationId, int limit) {
        List<GroupPlanPublicView> views = new ArrayList<>();
        for (GroupAgentPlan snapshot : planMapper.selectByConversation(conversationId, limit)) {
            GroupPlanPublicView view = project(snapshot);
            if (view != null) {
                views.add(view);
            }
        }
        return views;
    }

    public GroupPlanPublicView get(Long conversationId, Long planId) {
        GroupAgentPlan snapshot = planMapper.selectById(planId);
        if (snapshot == null || !snapshot.getGroupConversationId().equals(conversationId)) {
            return null;
        }
        return project(snapshot);
    }

    /** 仅发起人可继续规划；sourceMapId 必须来自服务端持久化，不接受前端 plan.sourceMapIds */
    public GroupPlanHandoff handoff(Long conversationId, Long planId, Long userId) {
        GroupAgentPlan snapshot = planMapper.selectById(planId);
        if (snapshot == null || !snapshot.getGroupConversationId().equals(conversationId)) {
            return null;
        }
        if (!snapshot.getAskerId().equals(userId)) {
            return null;
        }
        AiConversation conversation = conversationMapper.selectById(snapshot.getAiConversationId());
        if (conversation == null || conversation.getSourceMapId() == null) {
            return null;
        }
        return new GroupPlanHandoff(snapshot.getId(), snapshot.getAiConversationId(),
                conversation.getSourceMapId(), conversation.getSourceGroupConversationId());
    }

    private GroupPlanPublicView project(GroupAgentPlan snapshot) {
        PlanDto plan;
        try {
            plan = objectMapper.readValue(snapshot.getPlanJson(), PlanDto.class);
        } catch (Exception ex) {
            log.warn("群规划快照解析失败 planId={}：{}", snapshot.getId(), ex.toString());
            return null;
        }
        if (plan == null) {
            return null;
        }
        GroupPlanPublicView view = new GroupPlanPublicView();
        view.setPlanId(snapshot.getId());
        view.setConversationId(snapshot.getGroupConversationId());
        view.setMessageId(snapshot.getMessageId());
        view.setPlanVersion(snapshot.getPlanVersion() == null ? 1L : snapshot.getPlanVersion().longValue());
        view.setAskerId(snapshot.getAskerId());
        User asker = userMapper.selectById(snapshot.getAskerId());
        view.setAskerNickname(asker == null ? "" : (StringUtils.hasText(asker.getNickname())
                ? asker.getNickname() : asker.getUsername()));
        AiConversation source = conversationMapper.selectById(snapshot.getAiConversationId());
        view.setSavedActivityId(source == null ? null : source.getSavedActivityId());

        view.setTitle(plan.getTitle());
        view.setCategory(plan.getCategory());
        view.setSummary(plan.getSummary());
        view.setStartTime(plan.getStartTime());
        view.setEndTime(plan.getEndTime());
        view.setMaxMembers(plan.getMaxMembers());
        view.setConstraintStatus(plan.getConstraintStatus());
        view.setRouteConfidence(plan.getRouteConfidence());
        view.setTotalTravelMinutes(plan.getTotalTravelMinutes());
        view.setReason(plan.getReason());
        if (plan.getWarnings() != null) {
            view.setWarnings(new ArrayList<>(plan.getWarnings()));
        }

        if (plan.getStops() != null) {
            for (PlanStop stop : plan.getStops()) {
                // 实时起点/返程点不进入群摘要
                if (PlanSource.USER_LOCATION.equals(stop.getSource())) {
                    continue;
                }
                GroupPlanPublicView.StopView row = new GroupPlanPublicView.StopView();
                row.setSeq(stop.getSeq());
                row.setName(stop.getName());
                row.setStartTime(stop.getStartTime());
                row.setEndTime(stop.getEndTime());
                row.setTransportMode(stop.getTransportMode());
                row.setStayMinutes(stop.getStayMinutes());
                boolean locationVisible = PlanSource.GROUP_MAP.equals(stop.getSource())
                        || PlanSource.AMAP_POI.equals(stop.getSource());
                row.setLocationVisible(locationVisible);
                if (locationVisible) {
                    row.setPlaceId(stop.getPlaceId());
                    row.setLongitude(stop.getLongitude());
                    row.setLatitude(stop.getLatitude());
                }
                view.getStops().add(row);
            }
        }
        if (plan.getMembers() != null) {
            for (PlanDto.PlanMember member : plan.getMembers()) {
                GroupPlanPublicView.MemberView row = new GroupPlanPublicView.MemberView();
                row.setUserId(member.getUserId());
                row.setNickname(member.getNickname());
                row.setRole(member.getRole());
                row.setStatus(member.getStatus());
                view.getMembers().add(row);
            }
        }
        if (plan.getConflicts() != null) {
            for (PlanDto.PlanConflict conflict : plan.getConflicts()) {
                GroupPlanPublicView.ConflictView row = new GroupPlanPublicView.ConflictView();
                row.setUserId(conflict.getUserId());
                row.setNickname(conflict.getNickname());
                row.setStartTime(conflict.getStartTime());
                row.setEndTime(conflict.getEndTime());
                row.setSeverity(conflict.getSeverity());
                row.setOverlapMinutes(conflict.getOverlapMinutes());
                row.setSuggestion(conflict.getSuggestion());
                view.getConflicts().add(row);
            }
        }
        if (plan.getRouteSegments() != null) {
            for (PlanDto.PlanRouteSegment segment : plan.getRouteSegments()) {
                GroupPlanPublicView.SegmentView row = new GroupPlanPublicView.SegmentView();
                row.setFromSeq(segment.getFromSeq());
                row.setToSeq(segment.getToSeq());
                row.setTransportMode(segment.getTransportMode());
                row.setDistanceMeters(segment.getDistanceMeters());
                row.setDurationMinutes(segment.getDurationMinutes());
                view.getRouteSegments().add(row);
            }
        }
        return view;
    }
}
