package com.campus.place.mapper;

import com.campus.place.entity.Place;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface PlaceMapper {

    /** 地点去重格网命名锁（同一事务连接执行；提交/回滚后统一释放，见 PlaceGridLock） */
    @Select("SELECT GET_LOCK(#{key}, #{timeoutSeconds})")
    Integer getLock(@Param("key") String key, @Param("timeoutSeconds") int timeoutSeconds);

    @Select("SELECT RELEASE_LOCK(#{key})")
    Integer releaseLock(@Param("key") String key);

    int insert(Place place);

    Place selectById(@Param("id") Long id);

    List<Place> selectInBoundingBox(@Param("minLat") BigDecimal minLat,
                                    @Param("maxLat") BigDecimal maxLat,
                                    @Param("minLng") BigDecimal minLng,
                                    @Param("maxLng") BigDecimal maxLng);

    List<Place> selectInBoundingBoxLimited(@Param("minLat") BigDecimal minLat,
                                           @Param("maxLat") BigDecimal maxLat,
                                           @Param("minLng") BigDecimal minLng,
                                           @Param("maxLng") BigDecimal maxLng,
                                           @Param("limit") int limit);

    int updateStatus(@Param("id") Long id, @Param("status") int status);

    int update(Place place);

    List<com.campus.admin.dto.AdminPlaceItem> selectAdminPage(@Param("offset") int offset, @Param("limit") int limit);

    long countAll();

    long countMembers(@Param("placeId") Long placeId);

    long countPosts(@Param("placeId") Long placeId);

    long countLikes(@Param("placeId") Long placeId);

    List<com.campus.place.dto.PlaceBriefResponse> searchPlaces(@Param("pattern") String pattern,
                                                              @Param("limit") int limit);
}
