package com.campus.report.service.impl;

import com.campus.common.exception.BusinessException;
import com.campus.common.result.PageResult;
import com.campus.common.result.ResultCode;
import com.campus.common.util.PageUtils;
import com.campus.place.entity.Place;
import com.campus.place.service.PlaceService;
import com.campus.post.entity.Post;
import com.campus.post.entity.PostComment;
import com.campus.post.mapper.PostCommentMapper;
import com.campus.post.mapper.PostMapper;
import com.campus.post.service.PostCommentService;
import com.campus.post.service.PostService;
import com.campus.report.dto.CreateReportRequest;
import com.campus.report.dto.HandleReportRequest;
import com.campus.report.dto.ReportResponse;
import com.campus.report.entity.Report;
import com.campus.report.mapper.ReportMapper;
import com.campus.report.service.ReportService;
import com.campus.security.user.SecurityUtils;
import com.campus.user.entity.User;
import com.campus.user.mapper.UserMapper;
import com.campus.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final ReportMapper reportMapper;
    private final PostMapper postMapper;
    private final PostCommentMapper postCommentMapper;
    private final UserMapper userMapper;
    private final PlaceService placeService;
    private final PostService postService;
    private final PostCommentService postCommentService;
    private final UserService userService;

    @Override
    @Transactional
    public ReportResponse create(CreateReportRequest request) {
        String type = normalizeType(request.getTargetType());
        ensureTargetExists(type, request.getTargetId());
        Report report = new Report();
        report.setReporterId(SecurityUtils.getUserId());
        report.setTargetType(type);
        report.setTargetId(request.getTargetId());
        report.setReason(request.getReason().trim());
        report.setStatus(0);
        reportMapper.insert(report);
        return toResponse(reportMapper.selectById(report.getId()));
    }

    @Override
    public PageResult<ReportResponse> list(Integer status, Integer page, Integer size) {
        int p = PageUtils.normalizePage(page);
        int s = PageUtils.normalizeSize(size);
        List<ReportResponse> list = reportMapper.selectPage(status, PageUtils.offset(p, s), s).stream()
                .map(this::toResponse)
                .toList();
        return PageResult.of(list, p, s, reportMapper.count(status));
    }

    @Override
    @Transactional
    public ReportResponse handle(Long id, HandleReportRequest request) {
        Report report = reportMapper.selectById(id);
        if (report == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "举报不存在");
        }
        if (report.getStatus() == null || report.getStatus() != 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "举报已处理");
        }
        String decision = request.getDecision().trim().toUpperCase();
        String action = request.getAction() == null ? "NONE" : request.getAction().trim().toUpperCase();
        if ("REJECT".equals(decision)) {
            report.setStatus(2);
            report.setHandleAction("NONE");
        } else if ("APPROVE".equals(decision)) {
            applyAction(report, action);
            report.setStatus(1);
            report.setHandleAction(action);
        } else {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "decision 仅支持 APPROVE 或 REJECT");
        }
        report.setHandlerId(SecurityUtils.getUserId());
        int updated = reportMapper.updateHandled(report);
        if (updated == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "举报已处理");
        }
        return toResponse(reportMapper.selectById(id));
    }

    private void applyAction(Report report, String action) {
        switch (action) {
            case "NONE" -> {
            }
            case "DELETE_CONTENT" -> deleteContent(report);
            case "BAN_USER" -> userService.updateStatus(resolveUserId(report), 0);
            default -> throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "action 无效");
        }
    }

    private void deleteContent(Report report) {
        switch (report.getTargetType()) {
            case "POST" -> postService.hidePost(report.getTargetId());
            case "COMMENT" -> postCommentService.deleteComment(report.getTargetId());
            case "PLACE" -> placeService.hidePlace(report.getTargetId());
            default -> throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "用户举报请使用封禁");
        }
    }

    private Long resolveUserId(Report report) {
        return switch (report.getTargetType()) {
            case "POST" -> {
                Post post = postMapper.selectById(report.getTargetId());
                if (post == null) {
                    throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "动态不存在");
                }
                yield post.getUserId();
            }
            case "COMMENT" -> {
                PostComment comment = postCommentMapper.selectById(report.getTargetId());
                if (comment == null) {
                    throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "评论不存在");
                }
                yield comment.getUserId();
            }
            case "PLACE" -> {
                Place place = placeService.requireActivePlace(report.getTargetId());
                yield place.getCreatorId();
            }
            case "USER" -> report.getTargetId();
            default -> throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "举报目标无效");
        };
    }

    private void ensureTargetExists(String type, Long targetId) {
        switch (type) {
            case "POST" -> {
                Post post = postMapper.selectById(targetId);
                if (post == null || post.getStatus() == null || post.getStatus() != 1) {
                    throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "动态不存在");
                }
            }
            case "COMMENT" -> {
                if (postCommentMapper.selectById(targetId) == null) {
                    throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "评论不存在");
                }
            }
            case "PLACE" -> placeService.requireActivePlace(targetId);
            case "USER" -> {
                User user = userMapper.selectById(targetId);
                if (user == null) {
                    throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
                }
            }
            default -> throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "targetType 无效");
        }
    }

    private String normalizeType(String targetType) {
        if (targetType == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "targetType 无效");
        }
        return targetType.trim().toUpperCase();
    }

    private ReportResponse toResponse(Report report) {
        ReportResponse response = new ReportResponse();
        response.setId(report.getId());
        response.setReporterId(report.getReporterId());
        response.setTargetType(report.getTargetType());
        response.setTargetId(report.getTargetId());
        response.setReason(report.getReason());
        response.setStatus(report.getStatus());
        response.setHandlerId(report.getHandlerId());
        response.setHandleAction(report.getHandleAction());
        response.setCreatedAt(report.getCreatedAt());
        response.setHandledAt(report.getHandledAt());
        return response;
    }
}
