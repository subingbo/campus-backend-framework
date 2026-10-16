package com.campus.search.dto;

import com.campus.place.dto.PlaceBriefResponse;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SearchResponse {

    private List<PlaceBriefResponse> places = new ArrayList<>();
}
