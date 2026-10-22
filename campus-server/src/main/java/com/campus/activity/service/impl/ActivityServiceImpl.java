package com.campus.activity.service.impl;

import com.campus.activity.dto.ActivityInviteItem;
import com.campus.activity.dto.ActivityMemberItem;
import com.campus.activity.dto.ActivityResponse;
import com.campus.activity.dto.ActivityStopItem;
import com.campus.activity.dto.ActivityStopRequest;
import com.campus.activity.dto.CreateActivityRequest;
import com.campus.activity.dto.UpdateActivityRequest;
import com.campus.activity.entity.Activity;
import com.campus.activity.entity.ActivityStop;
import com.campus.activity.mapper.ActivityInviteMapper;
import com.campus.activity.mapper.ActivityMapper;
import com.campus.activity.mapper.ActivityMemberMapper;
import com.campus.activity.mapper.ActivityStopMapper;
import com.campus.activity.service.ActivityService;
import com.campus.activity.ws.ActivityEventNotifier;
import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.campus.place.entity.Place;
import com.campus.place.service.PlaceService;
import com.campus.security.user.SecurityUtils;
import com.campus.travel.ItineraryTravelChecker;
import com.campus.travel.TravelInfoProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityServiceImpl implements ActivityService {

    private static final int PLACE_LIST_LIMIT = 50;
    private static final int MINE_LIMIT = 200;
    private static final int INVITE_LIST_LIMIT = 50;
    /** 签到窗口：开始前 15 分钟起、开始后 15 分钟内可签 */
    private static final Duration CHECKIN_WINDOW = Duration.ofMinutes(15);

    private final ActivityMapper activityMapper;
    private final ActivityMemberMapper activityMemberMapper;
    private final ActivityInviteMapper activityInviteMapper;
    private final ActivityStopMapper activityStopMapper;
    private final PlaceService placeService;
    private final ActivityEventNotifier activityEventNotifier;
    private final TravelInfoProvider travelInfoProvider;
    private final SecureRandom random = new SecureRandom();

    @Override
    @Transactional
    public ActivityResponse create(CreateActivityRequest request) {
        placeService.requireActivePlace(request.getPlaceId());
        if (!request.getEndTime().isAfter(request.getStartTime())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "结束时间必须晚于开始时间");
        }
        // 只拒绝“仍将持续却把开始时间设在过去”的活动；完全历史的活动可作为记录录入
        if (request.getStartTime().isBefore(LocalDateTime.now().minusMinutes(2))
                && request.getEndTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "开始时间不能早于当前时间");
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
        activity.setCategory(normalizeCategory(request.getCategory()));
        activity.setCheckinCode(generateCheckinCode());
        activity.setCheckinOpenUntil(request.getStartTime().plus(CHECKIN_WINDOW));
        activity.setStatus(1);
        activityMapper.insert(activity);
        activityMemberMapper.insert(activity.getId(), userId);
        saveStops(activity.getId(), request.getStops(), activity.getStartTime(), activity.getEndTime());
        return requireView(activity.getId(), userId);
    }

    @Override
    public ActivityResponse get(Long id) {
        // 与 /activities/mine 保持一致：已取消活动仍可查看详情，但所有写操作仍拒绝
        return requireViewAnyStatus(id, SecurityUtils.getUserId());
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
        // 容量判定与插入必须在同一把活动行锁内完成，否则 count-then-insert 会超员
        Activity activity = activityMapper.selectByIdForUpdate(id);
        if (activity == null || activity.getStatus() == null || activity.getStatus() != 1) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "活动不存在");
        }
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
        Long userId = SecurityUtils.getUserId();
        // 与签到共用成员行：已签到成员不能靠取消报名抹掉签到记录
        int deleted = activityMemberMapper.deleteIfNotCheckedIn(id, userId);
        if (deleted == 0) {
            Integer checkedIn = activityMemberMapper.selectCheckedIn(id, userId);
            if (checkedIn != null && checkedIn == 1) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "已签到，不能取消报名");
            }
            // 未报名：幂等返回当前视图
        }
        return requireView(id, userId);
    }

    @Override
    @Transactional
    public ActivityResponse update(Long id, UpdateActivityRequest request) {
        Activity activity = requireActive(id);
        Long userId = SecurityUtils.getUserId();
        if (!activity.getCreatorId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "只有创建者可以编辑活动");
        }
        LocalDateTime now = LocalDateTime.now();
        // 已开始的活动只冻结时间/行程，标题、描述等仍可维护
        if (!activity.getStartTime().isAfter(now) && scheduleChanged(activity, request)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "活动已开始，不能修改时间或行程");
        }
        LocalDateTime startTime = request.getStartTime() != null ? request.getStartTime() : activity.getStartTime();
        LocalDateTime endTime = request.getEndTime() != null ? request.getEndTime() : activity.getEndTime();
        if (!endTime.isAfter(startTime)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "结束时间必须晚于开始时间");
        }
        if (request.getStartTime() != null && request.getStartTime().isBefore(now.minusMinutes(2))
                && endTime.isAfter(now)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "开始时间不能早于当前时间");
        }
        if (request.getMaxMembers() != null
                && activityMemberMapper.countByActivityId(id) > request.getMaxMembers()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "人数上限不能小于当前已报名人数");
        }
        Activity patch = new Activity();
        patch.setId(id);
        patch.setTitle(request.getTitle());
        patch.setDescription(request.getDescription());
        patch.setStartTime(startTime);
        patch.setEndTime(endTime);
        patch.setMaxMembers(request.getMaxMembers());
        if (StringUtils.hasText(request.getCategory())) {
            patch.setCategory(normalizeCategory(request.getCategory()));
        }
        if (request.getStartTime() != null && !startTime.equals(activity.getStartTime())) {
            // 只有开始时间真正变化时才顺延签到窗口，避免标题编辑重置签到截止
            patch.setCheckinOpenUntil(startTime.plus(CHECKIN_WINDOW));
        }
        activityMapper.updateById(patch);
        // 站点与现有一致时不删旧插新：避免标题编辑触发无意义的交通重校验
        if (request.getStops() != null && stopsDiffer(id, request.getStops())) {
            activityStopMapper.deleteByActivityId(id);
            saveStops(id, request.getStops(), startTime, endTime);
        }
        return requireView(id, userId);
    }

    @Override
    @Transactional
    public ActivityResponse cancel(Long id) {
        Activity activity = requireActive(id);
        Long userId = SecurityUtils.getUserId();
        if (!activity.getCreatorId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "只有创建者可以取消活动");
        }
        activityMapper.updateStatus(id, 0);
        activityEventNotifier.notifyCancelled(id, activity.getTitle(), userId);
        return requireViewAnyStatus(id, userId);
    }

    @Override
    public List<ActivityMemberItem> members(Long id) {
        Long userId = SecurityUtils.getUserId();
        ActivityResponse activity = requireViewAnyStatus(id, userId);
        if (!activity.getCreatorId().equals(userId) && activityMemberMapper.exists(id, userId) == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "仅活动成员可以查看成员列表");
        }
        return activityMemberMapper.listByActivityId(id);
    }

    @Override
    @Transactional
    public ActivityResponse checkin(Long id, String code) {
        Activity activity = requireActive(id);
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "请输入签到码");
        }
        Long userId = SecurityUtils.getUserId();
        if (activityMemberMapper.exists(id, userId) == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "先报名才能签到");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime().minus(CHECKIN_WINDOW))) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "签到还没开始");
        }
        if (activity.getCheckinOpenUntil() != null && now.isAfter(activity.getCheckinOpenUntil())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "签到窗口已关闭");
        }
        if (!code.trim().equals(activity.getCheckinCode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "签到码不正确");
        }
        int updated = activityMemberMapper.checkin(id, userId, now);
        if (updated == 0) {
            Integer checkedIn = activityMemberMapper.selectCheckedIn(id, userId);
            if (checkedIn == null || checkedIn != 1) {
                // 报名行在 exists 与 update 之间被取消报名删除（或状态异常）
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "先报名才能签到");
            }
            // 已签到：幂等成功，不覆盖首次签到时间
        }
        return requireView(id, userId);
    }

    @Override
    public List<ActivityResponse> mine(String role) {
        Long userId = SecurityUtils.getUserId();
        List<ActivityResponse> rows = activityMapper.selectMine(userId, userId, MINE_LIMIT);
        if ("created".equalsIgnoreCase(role)) {
            rows = rows.stream().filter(r -> userId.equals(r.getCreatorId())).toList();
        } else if ("joined".equalsIgnoreCase(role)) {
            rows = rows.stream().filter(ActivityResponse::isJoined).toList();
        }
        withPhase(rows);
        fillStopsBatch(rows);
        return rows;
    }

    @Override
    @Transactional
    public ActivityInviteItem invite(Long activityId, Long inviteeId) {
        Activity activity = requireActive(activityId);
        Long userId = SecurityUtils.getUserId();
        if (!activity.getCreatorId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "只有创建者可以邀请成员");
        }
        if (!activity.getEndTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "活动已结束");
        }
        if (inviteeId.equals(userId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不能邀请自己");
        }
        if (activityMemberMapper.exists(activityId, inviteeId) > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "对方已在活动成员中");
        }
        if (activityInviteMapper.exists(activityId, inviteeId) > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "已邀请过该用户");
        }
        if (activityMemberMapper.countByActivityId(activityId) >= activity.getMaxMembers()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "剩余名额不足");
        }
        try {
            activityInviteMapper.insert(activityId, userId, inviteeId);
        } catch (DuplicateKeyException ignored) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "已邀请过该用户");
        }
        ActivityInviteItem invite = activityInviteMapper.selectByActivityAndInvitee(activityId, inviteeId);
        activityEventNotifier.notifyInvited(invite);
        return invite;
    }

    @Override
    @Transactional
    public ActivityInviteItem respondInvite(Long inviteId, boolean accept) {
        // 锁顺序：邀请行 -> 活动行；直接报名只锁活动行，不会与这里形成环
        ActivityInviteItem invite = activityInviteMapper.selectForUpdate(inviteId);
        if (invite == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "邀请不存在");
        }
        Long userId = SecurityUtils.getUserId();
        if (!invite.getInviteeId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "不是你的邀请");
        }
        if (invite.getStatus() == null || invite.getStatus() != 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "邀请已响应");
        }
        Activity activity = activityMapper.selectByIdForUpdate(invite.getActivityId());
        if (activity == null || activity.getStatus() == null || activity.getStatus() != 1) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "活动不存在或已取消");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!activity.getEndTime().isAfter(now)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "活动已结束");
        }
        if (accept) {
            if (activityMemberMapper.exists(invite.getActivityId(), userId) == 0
                    && activityMemberMapper.countByActivityId(invite.getActivityId()) >= activity.getMaxMembers()) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "活动名额已满");
            }
            try {
                activityMemberMapper.insert(invite.getActivityId(), userId);
            } catch (DuplicateKeyException ignored) {
                // 已在成员列表（如先报名后被邀请），接受只标记邀请状态
            }
        }
        int responded = activityInviteMapper.respond(inviteId, accept ? 1 : 2, now);
        if (responded == 0) {
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "邀请已响应，请刷新后重试");
        }
        if (accept) {
            activityEventNotifier.notifyInviteAccepted(activity.getId(), activity.getTitle(), userId);
        }
        return activityInviteMapper.selectById(inviteId);
    }

    @Override
    public List<ActivityInviteItem> pendingInvites() {
        return activityInviteMapper.selectPendingByInvitee(SecurityUtils.getUserId(), INVITE_LIST_LIMIT);
    }

    @Override
    public List<ActivityInviteItem> listInvites(Long activityId) {
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "活动不存在");
        }
        if (!activity.getCreatorId().equals(SecurityUtils.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "只有创建者可以查看邀请状态");
        }
        return activityInviteMapper.selectByActivityId(activityId, INVITE_LIST_LIMIT);
    }

    /** 已开始活动是否真的在改时间/行程；仅改标题等字段不应被冻结 */
    private boolean scheduleChanged(Activity activity, UpdateActivityRequest request) {
        if (request.getStartTime() != null && !request.getStartTime().equals(activity.getStartTime())) {
            return true;
        }
        if (request.getEndTime() != null && !request.getEndTime().equals(activity.getEndTime())) {
            return true;
        }
        if (request.getStops() == null) {
            return false;
        }
        return stopsDiffer(activity.getId(), request.getStops());
    }

    private boolean stopsDiffer(Long activityId, List<ActivityStopRequest> requested) {
        List<ActivityStopItem> existing = activityStopMapper.selectByActivityId(activityId);
        if (existing.size() != requested.size()) {
            return true;
        }
        for (int i = 0; i < existing.size(); i++) {
            if (!sameStop(existing.get(i), requested.get(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean sameStop(ActivityStopItem existing, ActivityStopRequest requested) {
        if (!Objects.equals(existing.getName(), requested.getName())
                || !Objects.equals(existing.getPlaceId(), requested.getPlaceId())) {
            return false;
        }
        if (requested.getPlaceId() == null
                && (!sameCoord(existing.getLongitude(), requested.getLongitude())
                || !sameCoord(existing.getLatitude(), requested.getLatitude()))) {
            return false;
        }
        return Objects.equals(existing.getStartTime(), requested.getStartTime())
                && Objects.equals(existing.getEndTime(), requested.getEndTime())
                && Objects.equals(existing.getTransportMode(), requested.getTransportMode())
                && Objects.equals(existing.getNote(), requested.getNote());
    }

    private boolean sameCoord(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
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
        response.setStops(activityStopMapper.selectByActivityId(id));
        fillPhase(response);
        return response;
    }

    /** 取消等场景：活动存在即返回（status=0 时 fillPhase 会给出 CANCELLED） */
    private ActivityResponse requireViewAnyStatus(Long id, Long userId) {
        ActivityResponse response = activityMapper.selectViewById(id, userId);
        if (response == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "活动不存在");
        }
        response.setStops(activityStopMapper.selectByActivityId(id));
        fillPhase(response);
        return response;
    }

    private List<ActivityResponse> withPhase(List<ActivityResponse> rows) {
        for (ActivityResponse row : rows) {
            fillPhase(row);
        }
        return rows;
    }

    /** 我的日程批量带出多站行程（日历详情时间线用） */
    private void fillStopsBatch(List<ActivityResponse> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<Long, List<ActivityStopItem>> byActivity = activityStopMapper
                .selectByActivityIds(rows.stream().map(ActivityResponse::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(ActivityStopItem::getActivityId));
        for (ActivityResponse row : rows) {
            row.setStops(byActivity.getOrDefault(row.getId(), List.of()));
        }
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

    private String normalizeCategory(String category) {
        return StringUtils.hasText(category) ? category : "MEETING";
    }

    private String generateCheckinCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    /**
     * 校验并落库多站行程：placeId 优先取地点坐标；站点时间须落在活动起止内且按序不回退。
     * 在活动插入之后调用，同一事务内任何校验失败都会整体回滚。
     */
    private void saveStops(Long activityId, List<ActivityStopRequest> stops,
                           LocalDateTime activityStart, LocalDateTime activityEnd) {
        if (stops == null || stops.isEmpty()) {
            return;
        }
        LocalDateTime reached = null;
        List<ActivityStop> entities = new ArrayList<>(stops.size());
        for (int i = 0; i < stops.size(); i++) {
            ActivityStopRequest item = stops.get(i);
            if (item.getPlaceId() == null
                    && (item.getLongitude() == null || item.getLatitude() == null)) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                        "行程站点「" + item.getName() + "」缺少地点或坐标");
            }
            validateStopCoordinate(item);
            Place place = item.getPlaceId() != null
                    ? placeService.requireActivePlace(item.getPlaceId())
                    : null;
            if (item.getStartTime() != null) {
                if (item.getStartTime().isBefore(activityStart)) {
                    throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                            "行程站点「" + item.getName() + "」早于活动开始时间");
                }
                if (reached != null && item.getStartTime().isBefore(reached)) {
                    throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "行程站点时间顺序不正确");
                }
                reached = item.getStartTime();
            }
            if (item.getEndTime() != null) {
                if (item.getStartTime() != null && item.getEndTime().isBefore(item.getStartTime())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                            "行程站点「" + item.getName() + "」结束时间早于开始时间");
                }
                if (item.getEndTime().isAfter(activityEnd)) {
                    throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                            "行程站点「" + item.getName() + "」晚于活动结束时间");
                }
                reached = item.getEndTime();
            }
            ActivityStop stop = new ActivityStop();
            stop.setActivityId(activityId);
            stop.setSeq(i + 1);
            stop.setName(item.getName().trim());
            if (place != null) {
                stop.setPlaceId(place.getId());
                stop.setLongitude(place.getLongitude());
                stop.setLatitude(place.getLatitude());
            } else {
                stop.setLongitude(item.getLongitude());
                stop.setLatitude(item.getLatitude());
            }
            stop.setStartTime(item.getStartTime());
            stop.setEndTime(item.getEndTime());
            stop.setTransportMode(item.getTransportMode());
            stop.setNote(item.getNote());
            entities.add(stop);
        }
        validateTravelFeasibility(activityId, entities);
        for (ActivityStop stop : entities) {
            activityStopMapper.insert(stop);
        }
    }

    /**
     * 手动入口与 AI 保存共用同一套交通三态规则：不可行硬阻断；
     * provider 异常降级放行并留日志（AI 路径仍保持严格拒绝）。
     */
    private void validateTravelFeasibility(Long activityId, List<ActivityStop> stops) {
        if (stops.size() < 2) {
            return;
        }
        List<ItineraryTravelChecker.Leg> legs = new ArrayList<>(stops.size());
        for (ActivityStop stop : stops) {
            ItineraryTravelChecker.Leg leg = new ItineraryTravelChecker.Leg();
            leg.setSeq(stop.getSeq());
            leg.setName(stop.getName());
            leg.setLongitude(stop.getLongitude() == null ? null : stop.getLongitude().doubleValue());
            leg.setLatitude(stop.getLatitude() == null ? null : stop.getLatitude().doubleValue());
            leg.setStartTime(stop.getStartTime());
            leg.setEndTime(stop.getEndTime());
            leg.setTransportMode(stop.getTransportMode());
            legs.add(leg);
        }
        ItineraryTravelChecker.Result result = new ItineraryTravelChecker(travelInfoProvider).check(legs);
        if (!result.getViolations().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    ItineraryTravelChecker.message(result.getViolations().get(0)));
        }
        if (!result.getUnverified().isEmpty()) {
            log.warn("活动行程交通未完全核验 activityId={} unverified={}", activityId,
                    result.getUnverified().size());
        }
    }

    private void validateStopCoordinate(ActivityStopRequest item) {
        if (item.getLongitude() == null || item.getLatitude() == null) {
            return;
        }
        double lng = item.getLongitude().doubleValue();
        double lat = item.getLatitude().doubleValue();
        if (lng < -180 || lng > 180 || lat < -90 || lat > 90) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                    "行程站点「" + item.getName() + "」坐标无效");
        }
    }
}
