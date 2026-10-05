package com.campus.place.service.impl;

import com.campus.place.dto.PlaceCategoryResponse;
import com.campus.place.entity.PlaceCategory;
import com.campus.place.mapper.PlaceCategoryMapper;
import com.campus.place.service.PlaceCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PlaceCategoryServiceImpl implements PlaceCategoryService {

    private final PlaceCategoryMapper placeCategoryMapper;

    @Override
    public List<PlaceCategoryResponse> listActive() {
        return placeCategoryMapper.selectActiveAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private PlaceCategoryResponse toResponse(PlaceCategory category) {
        PlaceCategoryResponse response = new PlaceCategoryResponse();
        response.setId(category.getId());
        response.setName(category.getName());
        response.setIcon(category.getIcon());
        response.setSort(category.getSort());
        return response;
    }
}
