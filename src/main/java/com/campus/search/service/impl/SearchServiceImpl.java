package com.campus.search.service.impl;

import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.campus.common.util.SqlLikeUtils;
import com.campus.place.mapper.PlaceMapper;
import com.campus.search.dto.SearchResponse;
import com.campus.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private static final int DEFAULT_LIMIT = 20;

    private final PlaceMapper placeMapper;

    @Override
    public SearchResponse search(String keyword, String type) {
        if (!StringUtils.hasText(keyword)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "keyword 不能为空");
        }
        String searchType = StringUtils.hasText(type) ? type : "place";
        if (!"place".equalsIgnoreCase(searchType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "暂仅支持 type=place");
        }
        String pattern = SqlLikeUtils.escapeLike(keyword.trim());
        SearchResponse response = new SearchResponse();
        response.setPlaces(placeMapper.searchPlaces(pattern, DEFAULT_LIMIT));
        return response;
    }
}
