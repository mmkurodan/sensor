package com.example.sensor;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class OnlineRouteService {

    private final Context appContext;

    public OnlineRouteService(Context context) {
        appContext = context.getApplicationContext();
    }

    public RouteResult fetchRoute(GeoPoint start, GeoPoint end) throws IOException {
        if (start == null || end == null) {
            return null;
        }

        String requestUrl = "https://router.project-osrm.org/route/v1/driving/"
                + formatCoordinate(start)
                + ";"
                + formatCoordinate(end)
                + "?overview=full&geometries=geojson&steps=false&alternatives=false";

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(requestUrl).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", appContext.getPackageName() + "/1.0");
            connection.connect();

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode + " でルート取得に失敗しました");
            }

            try (InputStream inputStream = connection.getInputStream()) {
                return parseRouteResult(readBody(inputStream));
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private RouteResult parseRouteResult(String responseBody) throws IOException {
        try {
            JSONObject response = new JSONObject(responseBody);
            JSONArray routes = response.optJSONArray("routes");
            if (routes == null || routes.length() == 0) {
                return null;
            }

            JSONObject route = routes.getJSONObject(0);
            JSONObject geometry = route.getJSONObject("geometry");
            JSONArray coordinates = geometry.getJSONArray("coordinates");
            List<GeoPoint> points = new ArrayList<>(coordinates.length());
            for (int i = 0; i < coordinates.length(); i++) {
                JSONArray coordinate = coordinates.getJSONArray(i);
                double longitude = coordinate.getDouble(0);
                double latitude = coordinate.getDouble(1);
                points.add(new GeoPoint(latitude, longitude));
            }

            return new RouteResult(
                    points,
                    route.optDouble("distance", Double.NaN),
                    route.optDouble("duration", Double.NaN)
            );
        } catch (JSONException e) {
            throw new IOException("ルート結果の解析に失敗しました", e);
        }
    }

    private String formatCoordinate(GeoPoint point) {
        return point.getLongitude() + "," + point.getLatitude();
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

    public static class RouteResult {
        private final List<GeoPoint> points;
        private final double distanceMeters;
        private final double durationSeconds;

        public RouteResult(List<GeoPoint> points, double distanceMeters, double durationSeconds) {
            this.points = points;
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
        }

        public List<GeoPoint> getPoints() {
            return points;
        }

        public double getDistanceMeters() {
            return distanceMeters;
        }

        public double getDurationSeconds() {
            return durationSeconds;
        }
    }
}
