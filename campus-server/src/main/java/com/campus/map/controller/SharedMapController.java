package com.campus.map.controller;

import com.campus.common.result.Result;
import com.campus.map.dto.AddMapPlaceRequest;
import com.campus.map.dto.CreatePlaceCommentRequest;
import com.campus.map.dto.MapDetailResponse;
import com.campus.map.dto.MapListItemResponse;
import com.campus.map.dto.MapPlaceAddResponse;
import com.campus.map.dto.MapPlaceItemResponse;
import com.campus.map.dto.PlaceCommentResponse;
import com.campus.map.dto.SharePlaceToMapRequest;
import com.campus.map.dto.UpdateMemberRoleRequest;
import com.campus.map.service.SharedMapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "共享地图")
@RestController
@RequestMapping("/api/maps")
@RequiredArgsConstructor
public class SharedMapController {

    private final SharedMapService sharedMapService;

    @Operation(summary = "我加入的地图")
    @GetMapping
    public Result<List<MapListItemResponse>> list() {
        return Result.success(sharedMapService.listMyMaps());
    }

    @Operation(summary = "地图详情")
    @GetMapping("/{id:\\d+}")
    public Result<MapDetailResponse> detail(@PathVariable Long id) {
        return Result.success(sharedMapService.getMapDetail(id));
    }

    @Operation(summary = "修改成员角色")
    @PutMapping("/{id:\\d+}/members/{userId:\\d+}")
    public Result<Void> updateMemberRole(@PathVariable Long id,
                                         @PathVariable Long userId,
                                         @Valid @RequestBody UpdateMemberRoleRequest request) {
        sharedMapService.updateMemberRole(id, userId, request);
        return Result.success(null);
    }

    @Operation(summary = "移除成员")
    @DeleteMapping("/{id:\\d+}/members/{userId:\\d+}")
    public Result<Void> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        sharedMapService.removeMember(id, userId);
        return Result.success(null);
    }

    @Operation(summary = "地图上的地点")
    @GetMapping("/{id:\\d+}/places")
    public Result<List<MapPlaceItemResponse>> listPlaces(@PathVariable Long id) {
        return Result.success(sharedMapService.listMapPlaces(id));
    }

    @Operation(summary = "向地图添加地点")
    @PostMapping("/{id:\\d+}/places")
    public Result<MapPlaceAddResponse> addPlace(@PathVariable Long id,
                                                @Valid @RequestBody AddMapPlaceRequest request) {
        return Result.success(sharedMapService.addPlaceToMap(id, request));
    }

    @Operation(summary = "分享已有地点到地图")
    @PostMapping("/{id:\\d+}/places/{placeId:\\d+}")
    public Result<MapPlaceAddResponse> sharePlace(@PathVariable Long id,
                                                  @PathVariable Long placeId,
                                                  @RequestBody(required = false) SharePlaceToMapRequest request) {
        return Result.success(sharedMapService.sharePlaceToMap(id, placeId, request));
    }

    @Operation(summary = "从地图移除地点")
    @DeleteMapping("/{id:\\d+}/places/{placeId:\\d+}")
    public Result<Void> removePlace(@PathVariable Long id, @PathVariable Long placeId) {
        sharedMapService.removePlaceFromMap(id, placeId);
        return Result.success(null);
    }

    @Operation(summary = "地点评论列表")
    @GetMapping("/{id:\\d+}/places/{placeId:\\d+}/comments")
    public Result<List<PlaceCommentResponse>> listComments(@PathVariable Long id, @PathVariable Long placeId) {
        return Result.success(sharedMapService.listPlaceComments(id, placeId));
    }

    @Operation(summary = "发表地点评论")
    @PostMapping("/{id:\\d+}/places/{placeId:\\d+}/comments")
    public Result<PlaceCommentResponse> addComment(@PathVariable Long id,
                                                   @PathVariable Long placeId,
                                                   @Valid @RequestBody CreatePlaceCommentRequest request) {
        return Result.success(sharedMapService.addPlaceComment(id, placeId, request));
    }
}
