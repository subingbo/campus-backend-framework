package com.campus.common.geo;

import com.campus.common.util.GeoUtils;
import com.campus.place.entity.Place;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Bounding box + Haversine filtering for nearby place candidates.
 */
public final class GeoCandidateQuery {

    private GeoCandidateQuery() {
    }

    public record BoundingBox(
            BigDecimal minLat,
            BigDecimal maxLat,
            BigDecimal minLng,
            BigDecimal maxLng) {
    }

    public record PlaceDistance(Place place, double distanceMeters) {
    }

    public static BoundingBox boundingBox(BigDecimal longitude, BigDecimal latitude, double radiusMeters) {
        double lat = latitude.doubleValue();
        double lng = longitude.doubleValue();
        double delta = GeoUtils.deltaDegreesForMeters(radiusMeters, lat);
        return new BoundingBox(
                BigDecimal.valueOf(lat - delta),
                BigDecimal.valueOf(lat + delta),
                BigDecimal.valueOf(lng - delta),
                BigDecimal.valueOf(lng + delta));
    }

    public static List<PlaceDistance> filterWithinMeters(
            List<Place> candidates,
            double longitude,
            double latitude,
            double maxMeters,
            Long excludePlaceId) {
        List<PlaceDistance> result = new ArrayList<>();
        for (Place place : candidates) {
            if (excludePlaceId != null && excludePlaceId.equals(place.getId())) {
                continue;
            }
            double distance = GeoUtils.distanceMeters(
                    longitude, latitude,
                    place.getLongitude().doubleValue(),
                    place.getLatitude().doubleValue());
            if (distance <= maxMeters) {
                result.add(new PlaceDistance(place, distance));
            }
        }
        result.sort(Comparator.comparingDouble(PlaceDistance::distanceMeters));
        return result;
    }
}
