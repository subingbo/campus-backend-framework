package com.campus.search.service;

import com.campus.search.dto.SearchResponse;

public interface SearchService {

    SearchResponse search(String keyword, String type);
}
