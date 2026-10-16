package com.campus.map.dto;

import com.campus.place.dto.PlaceBriefResponse;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MapPlaceAddResponse {

    private boolean added;
    private boolean duplicatePending;
    private MapPlaceItemResponse place;
    private List<PlaceBriefResponse> candidates = new ArrayList<>();
}
