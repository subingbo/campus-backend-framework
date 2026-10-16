package com.campus.search.controller;

import com.campus.common.result.Result;
import com.campus.search.dto.SearchResponse;
import com.campus.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "搜索")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @Operation(summary = "搜索")
    @GetMapping
    public Result<SearchResponse> search(
            @RequestParam String keyword,
            @RequestParam(required = false) String type) {
        return Result.success(searchService.search(keyword, type));
    }
}
