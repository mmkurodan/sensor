package com.example.sensor;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class OnlineAddressGeocoder {

    private final Context appContext;

    public OnlineAddressGeocoder(Context context) {
        appContext = context.getApplicationContext();
    }

    public List<SearchResult> search(String query, int limit) throws IOException {
        String trimmedQuery = query == null ? "" : query.trim();
        if (trimmedQuery.isEmpty()) {
            return new ArrayList<>();
        }

        Uri requestUri = new Uri.Builder()
                .scheme("https")
                .authority("nominatim.openstreetmap.org")
                .appendPath("search")
                .appendQueryParameter("format", "jsonv2")
                .appendQueryParameter("limit", String.valueOf(Math.max(1, limit)))
                .appendQueryParameter("countrycodes", "jp")
                .appendQueryParameter("accept-language", "ja")
                .appendQueryParameter("q", trimmedQuery)
                .build();

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(requestUri.toString()).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Language", "ja");
            connection.setRequestProperty("User-Agent", appContext.getPackageName() + "/1.0");
            connection.connect();

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode + " で住所検索に失敗しました");
            }

            try (InputStream inputStream = connection.getInputStream()) {
                return parseSearchResults(readBody(inputStream), trimmedQuery);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private List<SearchResult> parseSearchResults(String responseBody, String fallbackDisplayName) throws IOException {
        try {
            JSONArray results = new JSONArray(responseBody);
            List<SearchResult> parsedResults = new ArrayList<>();
            for (int i = 0; i < results.length(); i++) {
                JSONObject candidate = results.getJSONObject(i);
                String displayName = candidate.optString("display_name", fallbackDisplayName);
                double latitude = Double.parseDouble(candidate.getString("lat"));
                double longitude = Double.parseDouble(candidate.getString("lon"));
                parsedResults.add(new SearchResult(displayName, latitude, longitude));
            }
            return parsedResults;
        } catch (JSONException | NumberFormatException e) {
            throw new IOException("住所検索結果の解析に失敗しました", e);
        }
    }

    private String readBody(InputStream inputStream) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
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
