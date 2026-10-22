package com.campus.schedule.service;

import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.campus.map.entity.SharedMap;
import com.campus.map.mapper.MapMemberMapper;
import com.campus.map.mapper.SharedMapMapper;
import com.campus.schedule.dto.BusyEntryDto;
import com.campus.schedule.dto.GroupBusyDto;
import com.campus.schedule.dto.SaveScheduleRequest;
import com.campus.schedule.dto.ScheduleShareOption;
import com.campus.schedule.dto.ShareScheduleRequest;
import com.campus.schedule.entity.PersonalSchedule;
import com.campus.schedule.entity.ScheduleSharePermission;
import com.campus.schedule.mapper.ActivityBusyMapper;
import com.campus.schedule.mapper.PersonalScheduleMapper;
import com.campus.schedule.mapper.ScheduleSharePermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 个人日程 + 多源忙闲视图（计划 §4.3/§8）：
 * - 忙碌来源 = personal_schedule ∪ 已报名/已创建活动（status=1），去重交给区间合并求解器，不复制存储；
 * - 群体忙闲：本人全量；他人必须显式授予 FREE_BUSY（USER 或当前 GROUP 目标）——否则进 unknownUserIds，
 *   未授权成员不能视为有空；
 * - 授权查看者只能看到时段，不暴露标题/备注（BusyEntryDto.title 仅本人有值）。
 */
@Service
@RequiredArgsConstructor
public class ScheduleService {

    /** 单边最大查询窗口：防止异常大区间扫描 */
    private static final int MAX_RANGE_DAYS = 92;

    private final PersonalScheduleMapper personalScheduleMapper;
    private final ScheduleSharePermissionMapper sharePermissionMapper;
    private final ActivityBusyMapper activityBusyMapper;
    private final SharedMapMapper sharedMapMapper;
    private final MapMemberMapper mapMemberMapper;

    // ---------- 个人日程 CRUD ----------

    public List<PersonalSchedule> listMine(Long userId, LocalDateTime from, LocalDateTime to) {
        LocalDateTime[] window = normalizeWindow(from, to);
        return personalScheduleMapper.selectOverlap(userId, window[0], window[1]);
    }

    @Transactional
    public PersonalSchedule create(Long userId, SaveScheduleRequest request) {
        validateTimes(request.getStartTime(), request.getEndTime());
        PersonalSchedule schedule = new PersonalSchedule();
        schedule.setUserId(userId);
        schedule.setTitle(request.getTitle().trim());
        schedule.setStartTime(request.getStartTime());
        schedule.setEndTime(request.getEndTime());
        schedule.setNote(request.getNote());
        personalScheduleMapper.insert(schedule);
        // insert 只回填 id；回查一次让 createdAt 等数据库生成字段可用于前端展示
        return personalScheduleMapper.selectById(schedule.getId());
    }

    @Transactional
    public PersonalSchedule update(Long userId, Long id, SaveScheduleRequest request) {
        PersonalSchedule schedule = requireOwned(id, userId);
        validateTimes(request.getStartTime(), request.getEndTime());
        schedule.setTitle(request.getTitle().trim());
        schedule.setStartTime(request.getStartTime());
        schedule.setEndTime(request.getEndTime());
        schedule.setNote(request.getNote());
        personalScheduleMapper.update(schedule);
        return personalScheduleMapper.selectById(schedule.getId());
    }

    @Transactional
    public void delete(Long userId, Long id) {
        requireOwned(id, userId);
        personalScheduleMapper.deleteByIdAndUser(id, userId);
    }

    // ---------- 授权 ----------

    @Transactional
    public void setShare(Long userId, ShareScheduleRequest request) {
        String targetType = StringUtils.hasText(request.getTargetType())
                ? request.getTargetType().trim().toUpperCase() : "";
        if (!ScheduleSharePermission.TARGET_USER.equals(targetType)
                && !ScheduleSharePermission.TARGET_GROUP.equals(targetType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "授权对象类型无效");
        }
        boolean grant = !Boolean.FALSE.equals(request.getGrant());
        if (grant) {
            if (ScheduleSharePermission.TARGET_GROUP.equals(targetType)) {
                SharedMap map = sharedMapMapper.selectByGroupId(request.getTargetId());
                if (map == null) {
                    throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "群组不存在");
                }
                if (mapMemberMapper.selectByMapAndUser(map.getId(), userId) == null) {
                    throw new BusinessException(ResultCode.FORBIDDEN.getCode(),
                            "只能向自己加入的群组授权忙闲");
                }
            }
            sharePermissionMapper.upsert(userId, targetType, request.getTargetId(),
                    ScheduleSharePermission.LEVEL_FREE_BUSY);
        } else {
            sharePermissionMapper.delete(userId, targetType, request.getTargetId());
        }
    }

    /** 当前用户加入的群地图 + 是否已授予 FREE_BUSY（供我的日程页开关） */
    public List<ScheduleShareOption> listGroupShareOptions(Long userId) {
        return sharePermissionMapper.selectGroupOptions(userId);
    }

    /** owner 是否已把 FREE_BUSY 授予目标（USER 本人 / GROUP 群组） */
    public boolean canSeeFreeBusy(Long ownerId, String targetType, Long targetId) {
        return sharePermissionMapper.existsGrant(ownerId, targetType, targetId,
                ScheduleSharePermission.LEVEL_FREE_BUSY) > 0;
    }

    // ---------- 多源忙闲视图 ----------

    /** 本人完整视图：personal_schedule + 已报名活动（含标题，供工具/日历页） */
    public List<BusyEntryDto> myBusy(Long userId, LocalDateTime from, LocalDateTime to) {
        LocalDateTime[] window = normalizeWindow(from, to);
        List<BusyEntryDto> result = new ArrayList<>();
        result.addAll(personalScheduleMapper.selectOverlap(userId, window[0], window[1]).stream()
                .map(s -> {
                    BusyEntryDto entry = new BusyEntryDto();
                    entry.setUserId(userId);
                    entry.setStartTime(s.getStartTime());
                    entry.setEndTime(s.getEndTime());
                    entry.setSourceType("SCHEDULE");
                    entry.setSourceId(s.getId());
                    entry.setTitle(s.getTitle());
                    return entry;
                }).toList());
        result.addAll(activityBusyMapper.selectActivityBusy(userId, window[0], window[1]));
        return result;
    }

    /**
     * 群体忙闲（计划 §8.2）：viewer 为当前发起人，以 campus_group 的地图成员为参与集合。
     * 本人=KNOWN（完整）；他人：有 GROUP 授权 → KNOWN（仅时段）；无授权 → UNKNOWN。
     */
    public GroupBusyDto groupBusy(Long viewerId, Long groupId, LocalDateTime from, LocalDateTime to) {
        LocalDateTime[] window = normalizeWindow(from, to);
        SharedMap map = sharedMapMapper.selectByGroupId(groupId);
        if (map == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "群组不存在");
        }
        // 忙闲是群成员数据：查看者必须是该地图成员，不能只凭 groupId 读取陌生群的成员时段
        if (mapMemberMapper.selectByMapAndUser(map.getId(), viewerId) == null) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "不是该群成员");
        }
        List<Long> memberIds = mapMemberMapper.selectByMapId(map.getId()).stream()
                .map(m -> m.getUserId())
                .distinct()
                .toList();
        GroupBusyDto result = new GroupBusyDto();
        for (Long memberId : memberIds) {
            if (memberId.equals(viewerId)) {
                result.getKnown().addAll(myBusy(viewerId, window[0], window[1]));
                continue;
            }
            if (canSeeFreeBusy(memberId, ScheduleSharePermission.TARGET_GROUP, groupId)) {
                result.getKnown().addAll(busyWithoutTitles(memberId, window[0], window[1]));
            } else {
                result.getUnknownUserIds().add(memberId);
            }
        }
        return result;
    }

    private List<BusyEntryDto> busyWithoutTitles(Long userId, LocalDateTime from, LocalDateTime to) {
        List<BusyEntryDto> result = new ArrayList<>();
        result.addAll(personalScheduleMapper.selectOverlap(userId, from, to).stream()
                .map(s -> {
                    BusyEntryDto entry = new BusyEntryDto();
                    entry.setUserId(userId);
                    entry.setStartTime(s.getStartTime());
                    entry.setEndTime(s.getEndTime());
                    entry.setSourceType("SCHEDULE");
                    entry.setSourceId(s.getId());
                    // FREE_BUSY 授权：仅时段，不暴露标题
                    return entry;
                }).toList());
        List<BusyEntryDto> activityBusy = activityBusyMapper.selectActivityBusy(userId, from, to);
        activityBusy.forEach(e -> e.setTitle(null));
        result.addAll(activityBusy);
        return result;
    }

    private PersonalSchedule requireOwned(Long id, Long userId) {
        PersonalSchedule schedule = personalScheduleMapper.selectById(id);
        if (schedule == null || !schedule.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "日程不存在");
        }
        return schedule;
    }

    private void validateTimes(LocalDateTime start, LocalDateTime end) {
        if (!end.isAfter(start)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "结束时间必须晚于开始时间");
        }
        if (start.isBefore(LocalDateTime.now().minusMinutes(5))) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "日程开始时间已过去");
        }
    }

    /** 边界校验 + 截断到最大窗口 */
    private LocalDateTime[] normalizeWindow(LocalDateTime from, LocalDateTime to) {
        LocalDateTime safeFrom = from == null ? LocalDateTime.now().minusMinutes(5) : from;
        LocalDateTime safeTo = to == null ? safeFrom.plusDays(30) : to;
        if (!safeTo.isAfter(safeFrom)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "时间窗口无效");
        }
        if (safeTo.minusDays(MAX_RANGE_DAYS).isAfter(safeFrom)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "时间窗口最长 " + MAX_RANGE_DAYS + " 天");
        }
        return new LocalDateTime[]{safeFrom, safeTo};
    }
}
