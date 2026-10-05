package com.campus.place.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PlaceCreateResponse {

    private boolean created;
    private PlaceDetailResponse place;
    private List<PlaceBriefResponse> candidates = new ArrayList<>();
}
