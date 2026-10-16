package com.campus.place.service;

import com.campus.place.dto.PlaceCategoryResponse;

import java.util.List;

public interface PlaceCategoryService {

    List<PlaceCategoryResponse> listActive();
}
