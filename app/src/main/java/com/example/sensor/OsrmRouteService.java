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
import java.util.Locale;

public class OsrmRouteService {

    private static final String ROUTE_BASE_URL = "https://router.project-osrm.org/route/v1/driving/";

    private final String userAgent;

    public OsrmRouteService(Context context) {
        userAgent = context.getApplicationContext().getPackageName();
    }

    public RouteResult fetchRoute(GeoPoint origin, GeoPoint destination) throws IOException {
        if (origin == null || destination == null) {
            throw new IOException("経路の始点または終点がありません");
        }

        HttpURLConnection connection = null;
        try {
            String requestUrl = String.format(
                    Locale.US,
                    "%s%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=false",
                    ROUTE_BASE_URL,
                    origin.getLongitude(),
                    origin.getLatitude(),
                    destination.getLongitude(),
                    destination.getLatitude()
            );
            connection = (HttpURLConnection) new URL(requestUrl).openConnection();
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
                throw new IOException("経路検索サービスが応答しませんでした (" + responseCode + ")");
            }

            JSONObject root = new JSONObject(responseBody);
            if (!"Ok".equalsIgnoreCase(root.optString("code"))) {
                throw new IOException("経路検索に失敗しました");
            }

            JSONArray routes = root.optJSONArray("routes");
            if (routes == null || routes.length() == 0) {
                throw new IOException("利用可能な経路が見つかりません");
            }

            JSONObject route = routes.getJSONObject(0);
            JSONObject geometry = route.getJSONObject("geometry");
            JSONArray coordinates = geometry.optJSONArray("coordinates");
            if (coordinates == null || coordinates.length() == 0) {
                throw new IOException("経路の座標を取得できません");
            }

            List<GeoPoint> points = new ArrayList<>();
            for (int i = 0; i < coordinates.length(); i++) {
                JSONArray coordinate = coordinates.getJSONArray(i);
                if (coordinate.length() < 2) {
                    continue;
                }
                points.add(new GeoPoint(coordinate.getDouble(1), coordinate.getDouble(0)));
            }
            if (points.isEmpty()) {
                throw new IOException("経路の座標を取得できません");
            }

            return new RouteResult(
                    points,
                    route.optDouble("distance", Double.NaN),
                    route.optDouble("duration", Double.NaN)
            );
        } catch (JSONException e) {
            throw new IOException("経路検索結果の解析に失敗しました", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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

    public static class RouteResult {
        private final List<GeoPoint> points;
        private final double distanceMeters;
        private final double durationSeconds;

        public RouteResult(List<GeoPoint> points, double distanceMeters, double durationSeconds) {
            this.points = new ArrayList<>(points);
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
        }

        public List<GeoPoint> getPoints() {
            return new ArrayList<>(points);
        }

        public double getDistanceMeters() {
            return distanceMeters;
        }

        public double getDurationSeconds() {
            return durationSeconds;
        }
    }
}
