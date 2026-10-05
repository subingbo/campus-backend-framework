package com.campus.report.service;

import com.campus.common.result.PageResult;
import com.campus.report.dto.CreateReportRequest;
import com.campus.report.dto.HandleReportRequest;
import com.campus.report.dto.ReportResponse;

public interface ReportService {

    ReportResponse create(CreateReportRequest request);

    PageResult<ReportResponse> list(Integer status, Integer page, Integer size);

    ReportResponse handle(Long id, HandleReportRequest request);
}
