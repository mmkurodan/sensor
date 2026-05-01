package com.example.sensor;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public class PlatformAddressGeocoder {

    private final Geocoder geocoder;

    public PlatformAddressGeocoder(Context context) {
        geocoder = new Geocoder(context.getApplicationContext(), Locale.JAPAN);
    }

    public List<SearchResult> search(String query, int limit) throws IOException {
        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery.isEmpty()) {
            return new ArrayList<>();
        }
        if (!Geocoder.isPresent()) {
            throw new IOException("住所検索サービスを利用できません");
        }

        List<Address> addresses = geocoder.getFromLocationName(normalizedQuery, Math.max(1, limit));
        if (addresses == null || addresses.isEmpty()) {
            return new ArrayList<>();
        }

        LinkedHashMap<String, SearchResult> uniqueResults = new LinkedHashMap<>();
        for (Address address : addresses) {
            if (address == null || !address.hasLatitude() || !address.hasLongitude()) {
                continue;
            }

            String displayName = buildDisplayName(address, normalizedQuery);
            String key = normalizeForComparison(displayName)
                    + "@"
                    + String.format(Locale.US, "%.6f,%.6f", address.getLatitude(), address.getLongitude());
            uniqueResults.put(key, new SearchResult(displayName, address.getLatitude(), address.getLongitude()));
            if (uniqueResults.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(uniqueResults.values());
    }

    private String buildDisplayName(Address address, String fallbackDisplayName) {
        List<String> parts = new ArrayList<>();
        int maxIndex = address.getMaxAddressLineIndex();
        if (maxIndex >= 0) {
            for (int i = 0; i <= maxIndex; i++) {
                String line = address.getAddressLine(i);
                if (line != null && !line.trim().isEmpty()) {
                    parts.add(line.trim());
                }
            }
        }

        if (parts.isEmpty()) {
            addPart(parts, address.getFeatureName());
            addPart(parts, address.getThoroughfare());
            addPart(parts, address.getSubLocality());
            addPart(parts, address.getLocality());
            addPart(parts, address.getAdminArea());
            addPart(parts, address.getCountryName());
        }

        if (parts.isEmpty()) {
            return fallbackDisplayName;
        }
        return String.join(", ", parts);
    }

    private void addPart(List<String> parts, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (!trimmed.isEmpty() && !parts.contains(trimmed)) {
            parts.add(trimmed);
        }
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        return query
                .replace('\u3000', ' ')
                .trim()
                .replaceAll("\\s+", " ");
    }

    private String normalizeForComparison(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    public static class SearchResult {
        private final String displayName;
        private final double latitude;
        private final double longitude;

        public SearchResult(String displayName, double latitude, double longitude) {
            this.displayName = displayName;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public String getDisplayName() {
            return displayName;
        }

        public double getLatitude() {
            return latitude;
        }

        public double getLongitude() {
            return longitude;
        }
    }
}
