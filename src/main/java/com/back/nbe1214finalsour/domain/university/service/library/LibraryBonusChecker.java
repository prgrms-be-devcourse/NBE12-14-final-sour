package com.back.nbe1214finalsour.domain.university.service.library;

import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LibraryBonusChecker {

    private static final double RADIUS_METERS = 100.0;
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private final UniversityCache universityCache;

    public boolean isInLibrary(Long universityId, Double lat, Double lng) {
        if (lat == null || lng == null) {
            return false;
        }
        return universityCache.getLibrary(universityId)
                .filter(library -> distanceMeters(library.lat(), library.lng(), lat, lng) <= RADIUS_METERS)
                .isPresent();
    }

    private static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(a));
    }
}
