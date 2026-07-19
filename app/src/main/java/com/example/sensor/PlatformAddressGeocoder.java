package com.example.sensor;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public class PlatformAddressGeocoder {

    private final Geocoder geocoder;
    private final String userAgent;

    public PlatformAddressGeocoder(Context context) {
        geocoder = new Geocoder(context.getApplicationContext(), Locale.JAPAN);
        userAgent = MapServiceUserAgent.get(context.getApplicationContext());
    }

    public List<SearchResult> search(String query, int limit) throws IOException {
        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery.isEmpty()) {
            return new ArrayList<>();
        }

        int safeLimit = Math.max(1, limit);
        IOException platformFailure = null;

        if (Geocoder.isPresent()) {
            try {
                List<SearchResult> platformResults = searchWithPlatformGeocoder(normalizedQuery, safeLimit);
                if (!platformResults.isEmpty()) {
                    return platformResults;
                }
            } catch (IOException e) {
                platformFailure = e;
            }
        }

        try {
            return searchWithNominatim(normalizedQuery, safeLimit);
        } catch (IOException networkFailure) {
            if (platformFailure != null) {
                IOException combined = new IOException("住所検索に失敗しました");
                combined.addSuppressed(platformFailure);
                combined.addSuppressed(networkFailure);
                throw combined;
            }
            throw networkFailure;
        }
    }

    private List<SearchResult> searchWithPlatformGeocoder(String normalizedQuery, int limit) throws IOException {
        List<Address> addresses = geocoder.getFromLocationName(normalizedQuery, limit);
        if (addresses == null || addresses.isEmpty()) {
            return new ArrayList<>();
        }
        return deduplicatePlatformResults(addresses, normalizedQuery, limit);
    }

    private List<SearchResult> searchWithNominatim(String normalizedQuery, int limit) throws IOException {
        HttpURLConnection connection = null;
        try {
            String encodedQuery = URLEncoder.encode(normalizedQuery, StandardCharsets.UTF_8.name());
            URL url = new URL(
                    "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&accept-language=ja&limit="
                            + limit
                            + "&q="
                            + encodedQuery
            );
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept", "application/json");

            int responseCode = connection.getResponseCode();
            InputStream responseStream = responseCode >= 200 && responseCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String responseBody = readResponseBody(responseStream);

            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("住所検索サービスが応答しませんでした (" + responseCode + ")");
            }

            JSONArray resultsJson = new JSONArray(responseBody);
            LinkedHashMap<String, SearchResult> uniqueResults = new LinkedHashMap<>();
            for (int i = 0; i < resultsJson.length(); i++) {
                JSONObject item = resultsJson.getJSONObject(i);
                double latitude = Double.parseDouble(item.optString("lat", "NaN"));
                double longitude = Double.parseDouble(item.optString("lon", "NaN"));
                if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
                    continue;
                }

                String displayName = item.optString("display_name", normalizedQuery).trim();
                if (displayName.isEmpty()) {
                    displayName = normalizedQuery;
                }

                addUniqueResult(uniqueResults, new SearchResult(displayName, latitude, longitude));
                if (uniqueResults.size() >= limit) {
                    break;
                }
            }
            return new ArrayList<>(uniqueResults.values());
        } catch (JSONException e) {
            throw new IOException("住所検索結果の解析に失敗しました", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private List<SearchResult> deduplicatePlatformResults(List<Address> addresses, String fallbackDisplayName, int limit) {
        LinkedHashMap<String, SearchResult> uniqueResults = new LinkedHashMap<>();
        for (Address address : addresses) {
            if (address == null || !address.hasLatitude() || !address.hasLongitude()) {
                continue;
            }

            String displayName = buildDisplayName(address, fallbackDisplayName);
            addUniqueResult(uniqueResults, new SearchResult(displayName, address.getLatitude(), address.getLongitude()));
            if (uniqueResults.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(uniqueResults.values());
    }

    private void addUniqueResult(LinkedHashMap<String, SearchResult> uniqueResults, SearchResult result) {
        String key = normalizeForComparison(result.getDisplayName())
                + "@"
                + String.format(Locale.US, "%.6f,%.6f", result.getLatitude(), result.getLongitude());
        uniqueResults.put(key, result);
    }

    private String readResponseBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
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
