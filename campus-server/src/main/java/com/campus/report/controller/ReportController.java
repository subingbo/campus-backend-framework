package com.campus.report.controller;

import com.campus.common.result.Result;
import com.campus.report.dto.CreateReportRequest;
import com.campus.report.dto.ReportResponse;
import com.campus.report.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "举报")
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "提交举报")
    @PostMapping
    public Result<ReportResponse> create(@Valid @RequestBody CreateReportRequest request) {
        return Result.success(reportService.create(request));
    }
}
