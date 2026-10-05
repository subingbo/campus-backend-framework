package com.campus.place.controller;

import com.campus.common.result.Result;
import com.campus.place.dto.PlaceCategoryResponse;
import com.campus.place.service.PlaceCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "地点分类")
@RestController
@RequestMapping("/api/place-categories")
@RequiredArgsConstructor
public class PlaceCategoryController {

    private final PlaceCategoryService placeCategoryService;

    @Operation(summary = "地点分类列表")
    @GetMapping
    public Result<List<PlaceCategoryResponse>> list() {
        return Result.success(placeCategoryService.listActive());
    }
}
