package com.campus.place.controller;

import com.campus.common.ratelimit.RateLimiter;
import com.campus.common.result.Result;
import com.campus.place.dto.CreatePlaceRequest;
import com.campus.place.dto.PlaceBriefResponse;
import com.campus.place.dto.PlaceCreateResponse;
import com.campus.place.dto.PlaceDetailResponse;
import com.campus.place.dto.UpdatePlaceRequest;
import com.campus.place.service.PlaceService;
import com.campus.security.user.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

@Tag(name = "地点")
@RestController
@RequestMapping("/api/places")
@RequiredArgsConstructor
public class PlaceController {

    private final PlaceService placeService;
    private final RateLimiter rateLimiter;

    @Operation(summary = "创建地点")
    @PreAuthorize("hasAuthority('place:create')")
    @PostMapping
    public Result<PlaceCreateResponse> create(@Valid @RequestBody CreatePlaceRequest request) {
        Long userId = SecurityUtils.getUserId();
        rateLimiter.check("place:create:" + userId, 5, Duration.ofMinutes(1));
        return Result.success(placeService.createPlace(request));
    }

    @Operation(summary = "附近地点")
    @GetMapping("/nearby")
    public Result<List<PlaceBriefResponse>> nearby(
            @RequestParam BigDecimal longitude,
            @RequestParam BigDecimal latitude,
            @RequestParam(required = false) Integer radius) {
        return Result.success(placeService.nearby(longitude, latitude, radius));
    }

    @Operation(summary = "地图视野内地点")
    @GetMapping("/map")
    public Result<List<PlaceBriefResponse>> map(
            @RequestParam BigDecimal minLng,
            @RequestParam BigDecimal maxLng,
            @RequestParam BigDecimal minLat,
            @RequestParam BigDecimal maxLat) {
        return Result.success(placeService.map(minLng, maxLng, minLat, maxLat));
    }

    @Operation(summary = "地点详情")
    @GetMapping("/{id:\\d+}")
    public Result<PlaceDetailResponse> detail(@PathVariable Long id) {
        return Result.success(placeService.getDetail(id));
    }

    @Operation(summary = "编辑地点")
    @PutMapping("/{id:\\d+}")
    public Result<PlaceDetailResponse> update(@PathVariable Long id,
                                              @Valid @RequestBody UpdatePlaceRequest request) {
        return Result.success(placeService.updatePlace(id, request));
    }
}
