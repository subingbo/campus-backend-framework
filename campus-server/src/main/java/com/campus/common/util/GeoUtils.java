package com.campus.common.util;

public final class GeoUtils {

    private static final double EARTH_RADIUS_METERS = 6371000.0;

    private GeoUtils() {
    }

    /**
     * Haversine distance in meters between two WGS84 points.
     */
    public static double distanceMeters(double lon1, double lat1, double lon2, double lat2) {
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double deltaLat = Math.toRadians(lat2 - lat1);
        double deltaLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1Rad) * Math.cos(lat2Rad) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }

    /**
     * Approximate bounding box delta in degrees for a radius in meters at given latitude.
     */
    public static double deltaDegreesForMeters(double meters, double latitude) {
        double latDelta = meters / EARTH_RADIUS_METERS * (180.0 / Math.PI);
        double cosLat = Math.cos(Math.toRadians(latitude));
        double lonDelta = cosLat > 1e-6 ? latDelta / cosLat : latDelta;
        return Math.max(latDelta, lonDelta);
    }
}
