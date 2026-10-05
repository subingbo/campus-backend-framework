package com.campus.place.service;

import com.campus.place.dto.CreatePlaceRequest;
import com.campus.place.dto.PlaceBriefResponse;
import com.campus.place.dto.PlaceCreateResponse;
import com.campus.place.dto.PlaceDetailResponse;
import com.campus.place.dto.UpdatePlaceRequest;
import com.campus.place.entity.Place;

import java.math.BigDecimal;
import java.util.List;

public interface PlaceService {

    PlaceCreateResponse createPlace(CreatePlaceRequest request);

    List<PlaceBriefResponse> nearby(BigDecimal longitude, BigDecimal latitude, Integer radius);

    PlaceDetailResponse getDetail(Long id);

    Place requireActivePlace(Long id);

    List<PlaceBriefResponse> map(BigDecimal minLng, BigDecimal maxLng, BigDecimal minLat, BigDecimal maxLat);

    void hidePlace(Long id);

    PlaceDetailResponse updatePlace(Long id, UpdatePlaceRequest request);

    boolean canUserEditPlace(Long userId, Long placeId);
}
