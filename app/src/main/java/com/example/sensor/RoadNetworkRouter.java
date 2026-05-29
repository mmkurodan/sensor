package com.example.sensor;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RoadNetworkRouter {

    private static final String[] OVERPASS_ENDPOINTS = new String[]{
            "https://overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"
    };
    private static final String ROUTE_SUMMARY_DRIVING = "自動車経路";
    private static final String ROUTE_SUMMARY_WALKING = "徒歩経路";
    private static final String ROUTE_SUMMARY_EXPRESSWAY = "高速優先";
    private static final double ROUTE_PADDING_RATIO = 0.20d;
    private static final double MIN_PADDING_METERS = 120d;
    private static final double MAX_PADDING_METERS = 5_000d;
    private static final double MAX_ENDPOINT_SNAP_DISTANCE_METERS = 400d;
    private static final double DEFAULT_APPROACH_SPEED_METERS_PER_SECOND = 8.33d;
    private static final double DEFAULT_WALKING_SPEED_METERS_PER_SECOND = 1.39d;
    private static final double MAX_ROUTE_SPEED_METERS_PER_SECOND = 33.33d;
    private static final double MAX_WALKING_ROUTE_SPEED_METERS_PER_SECOND = 1.67d;
    private static final double HIGHWAY_ROUTE_THRESHOLD_METERS = 10_000d;
    private static final long MAX_DOWNLOAD_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_INTERCHANGE_CANDIDATES = 4;
    private static final Pattern SPEED_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)");

    private final String userAgent;

    private RoadNetwork cachedDrivingNetwork;
    private RoadNetwork cachedWalkingNetwork;
    private RoadNetwork cachedMotorwayNetwork;

    public RoadNetworkRouter(Context context) {
        userAgent = context.getApplicationContext().getPackageName();
    }

    public synchronized RouteResult findRoute(GeoPoint origin, GeoPoint destination) throws IOException {
        return findRoute(origin, destination, TravelMode.DRIVING);
    }

    public synchronized RouteResult findRoute(
            GeoPoint origin,
            GeoPoint destination,
            TravelMode travelMode
    ) throws IOException {
        if (origin == null || destination == null) {
            throw new IllegalArgumentException("経路の始点または終点がありません");
        }

        TravelMode effectiveTravelMode = travelMode == null ? TravelMode.DRIVING : travelMode;
        QueryBounds queryBounds = QueryBounds.from(origin, destination);
        if (effectiveTravelMode == TravelMode.WALKING) {
            RoadNetwork walkingNetwork = getRoadNetwork(RouteProfile.WALKING, queryBounds);
            return walkingNetwork.findRoute(origin, destination, ROUTE_SUMMARY_WALKING);
        }

        if (origin.distanceToAsDouble(destination) >= HIGHWAY_ROUTE_THRESHOLD_METERS) {
            try {
                RouteResult expresswayPreferredRoute = findExpresswayPreferredRoute(
                        origin,
                        destination,
                        queryBounds
                );
                if (expresswayPreferredRoute != null) {
                    return expresswayPreferredRoute;
                }
            } catch (IOException ignored) {
                // Fall back to the standard drivable road route when motorway routing is unavailable.
            }
        }

        RoadNetwork drivingNetwork = getRoadNetwork(RouteProfile.DRIVING, queryBounds);
        return drivingNetwork.findRoute(origin, destination, ROUTE_SUMMARY_DRIVING);
    }

    public synchronized void clearCache() {
        cachedDrivingNetwork = null;
        cachedWalkingNetwork = null;
        cachedMotorwayNetwork = null;
    }

    private RouteResult findExpresswayPreferredRoute(
            GeoPoint origin,
            GeoPoint destination,
            QueryBounds routeBounds
    ) throws IOException {
        RoadNetwork motorwayNetwork = getRoadNetwork(RouteProfile.MOTORWAY, routeBounds);
        List<InterchangeCandidate> originCandidates = collectInterchangeCandidates(origin, motorwayNetwork);
        List<InterchangeCandidate> destinationCandidates = collectInterchangeCandidates(destination, motorwayNetwork);
        if (originCandidates.isEmpty() || destinationCandidates.isEmpty()) {
            return null;
        }

        HashMap<Long, RoadNetwork> originApproachNetworks = new HashMap<>();
        HashMap<Long, RoadNetwork> destinationApproachNetworks = new HashMap<>();
        RouteResult bestRoute = null;
        double bestDurationSeconds = Double.POSITIVE_INFINITY;
        for (InterchangeCandidate originCandidate : originCandidates) {
            RoadNetwork originDrivingNetwork = getApproachNetwork(
                    originApproachNetworks,
                    origin,
                    originCandidate.motorwayNode
            );
            GraphNode originDrivingNode = originDrivingNetwork.findNodeById(originCandidate.motorwayNode.id);
            if (originDrivingNode == null) {
                continue;
            }
            for (InterchangeCandidate destinationCandidate : destinationCandidates) {
                try {
                    RoadNetwork destinationDrivingNetwork = getApproachNetwork(
                            destinationApproachNetworks,
                            destination,
                            destinationCandidate.motorwayNode
                    );
                    GraphNode destinationDrivingNode = destinationDrivingNetwork.findNodeById(destinationCandidate.motorwayNode.id);
                    if (destinationDrivingNode == null) {
                        continue;
                    }

                    RouteResult originApproach = originDrivingNetwork.findRoute(
                            origin,
                            originDrivingNode,
                            ROUTE_SUMMARY_EXPRESSWAY
                    );
                    RouteResult motorwaySegment = motorwayNetwork.findRoute(
                            originCandidate.motorwayNode,
                            destinationCandidate.motorwayNode,
                            ROUTE_SUMMARY_EXPRESSWAY
                    );
                    RouteResult destinationApproach = destinationDrivingNetwork.findRoute(
                            destinationDrivingNode,
                            destination,
                            ROUTE_SUMMARY_EXPRESSWAY
                    );

                    RouteResult combinedRoute = combineRouteResults(
                            ROUTE_SUMMARY_EXPRESSWAY,
                            originApproach,
                            motorwaySegment,
                            destinationApproach
                    );
                    if (combinedRoute.getDurationSeconds() < bestDurationSeconds) {
                        bestDurationSeconds = combinedRoute.getDurationSeconds();
                        bestRoute = combinedRoute;
                    }
                } catch (IOException ignored) {
                    // Try the next interchange combination.
                }
            }
        }
        return bestRoute;
    }

    private RoadNetwork getApproachNetwork(
            HashMap<Long, RoadNetwork> approachNetworks,
            GeoPoint endpoint,
            GraphNode interchangeNode
    ) throws IOException {
        RoadNetwork cachedNetwork = approachNetworks.get(interchangeNode.id);
        if (cachedNetwork != null) {
            return cachedNetwork;
        }

        RoadNetwork approachNetwork = getRoadNetwork(
                RouteProfile.DRIVING,
                QueryBounds.from(endpoint, interchangeNode.point)
        );
        approachNetworks.put(interchangeNode.id, approachNetwork);
        return approachNetwork;
    }

    private List<InterchangeCandidate> collectInterchangeCandidates(
            GeoPoint point,
            RoadNetwork motorwayNetwork
    ) {
        List<InterchangeCandidate> candidates = new ArrayList<>();
        for (GraphNode motorwayNode : motorwayNetwork.getInterchangeNodes()) {
            candidates.add(new InterchangeCandidate(
                    motorwayNode,
                    point.distanceToAsDouble(motorwayNode.point)
            ));
        }
        candidates.sort(Comparator.comparingDouble(candidate -> candidate.straightLineDistanceMeters));
        if (candidates.size() <= MAX_INTERCHANGE_CANDIDATES) {
            return candidates;
        }
        return new ArrayList<>(candidates.subList(0, MAX_INTERCHANGE_CANDIDATES));
    }

    private RouteResult combineRouteResults(String summaryLabel, RouteResult... partialRoutes) {
        List<GeoPoint> combinedPoints = new ArrayList<>();
        List<RoadSegment> combinedRoadSegments = new ArrayList<>();
        HashSet<String> segmentKeys = new HashSet<>();
        double totalDistanceMeters = 0d;
        double totalDurationSeconds = 0d;

        for (RouteResult partialRoute : partialRoutes) {
            if (partialRoute == null) {
                continue;
            }
            totalDistanceMeters += partialRoute.distanceMeters;
            totalDurationSeconds += partialRoute.durationSeconds;

            for (GeoPoint point : partialRoute.points) {
                addPointIfSeparated(combinedPoints, point);
            }
            for (RoadSegment segment : partialRoute.roadSegments) {
                String segmentKey = buildSegmentKey(segment.startPoint, segment.endPoint);
                if (segmentKeys.add(segmentKey)) {
                    combinedRoadSegments.add(segment);
                }
            }
        }

        return new RouteResult(
                combinedPoints,
                totalDistanceMeters,
                totalDurationSeconds,
                combinedRoadSegments,
                summaryLabel
        );
    }

    private String buildSegmentKey(GeoPoint startPoint, GeoPoint endPoint) {
        String startKey = String.format(Locale.US, "%.6f:%.6f", startPoint.getLatitude(), startPoint.getLongitude());
        String endKey = String.format(Locale.US, "%.6f:%.6f", endPoint.getLatitude(), endPoint.getLongitude());
        return startKey.compareTo(endKey) <= 0
                ? startKey + "|" + endKey
                : endKey + "|" + startKey;
    }

    private RoadNetwork getRoadNetwork(RouteProfile profile, QueryBounds queryBounds) throws IOException {
        RoadNetwork cachedNetwork = profile == RouteProfile.MOTORWAY
                ? cachedMotorwayNetwork
                : profile == RouteProfile.WALKING
                ? cachedWalkingNetwork
                : cachedDrivingNetwork;
        if (cachedNetwork != null && cachedNetwork.covers(queryBounds)) {
            return cachedNetwork;
        }

        RoadNetwork downloadedNetwork = downloadRoadNetwork(profile, queryBounds);
        if (profile == RouteProfile.MOTORWAY) {
            cachedMotorwayNetwork = downloadedNetwork;
        } else if (profile == RouteProfile.WALKING) {
            cachedWalkingNetwork = downloadedNetwork;
        } else {
            cachedDrivingNetwork = downloadedNetwork;
        }
        return downloadedNetwork;
    }

    private RoadNetwork downloadRoadNetwork(RouteProfile profile, QueryBounds queryBounds) throws IOException {
        IOException lastException = null;
        String query = buildOverpassQuery(profile, queryBounds);
        for (RequestFormat requestFormat : RequestFormat.values()) {
            for (String endpoint : OVERPASS_ENDPOINTS) {
                try {
                    return downloadRoadNetwork(endpoint, requestFormat, profile, queryBounds, query);
                } catch (IOException e) {
                    lastException = e;
                }
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new IOException("道路データの取得に失敗しました");
    }

    private RoadNetwork downloadRoadNetwork(
            String endpoint,
            RequestFormat requestFormat,
            RouteProfile profile,
            QueryBounds queryBounds,
            String query
    ) throws IOException {
        HttpURLConnection connection = null;
        try {
            byte[] requestBody = requestFormat == RequestFormat.FORM_URLENCODED
                    ? ("data=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name())).getBytes(StandardCharsets.UTF_8)
                    : query.getBytes(StandardCharsets.UTF_8);

            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(25_000);
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Content-Type", requestFormat.contentType);
            connection.setFixedLengthStreamingMode(requestBody.length);

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(requestBody);
            }

            int responseCode = connection.getResponseCode();
            InputStream responseStream = responseCode >= 200 && responseCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            byte[] responseBytes = readResponseBytes(responseStream);
            String responseBody = new String(responseBytes, StandardCharsets.UTF_8);

            if (responseCode < 200 || responseCode >= 300) {
                throw createResponseException(endpoint, requestFormat, responseCode, responseBody);
            }

            String contentType = connection.getContentType();
            if (contentType != null && contentType.toLowerCase(Locale.US).contains("html")) {
                throw new IOException("道路データAPIが想定外の応答を返しました (" + endpoint + ")");
            }

            return parseRoadNetwork(profile, queryBounds, responseBody);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private IOException createResponseException(
            String endpoint,
            RequestFormat requestFormat,
            int responseCode,
            String responseBody
    ) {
        StringBuilder message = new StringBuilder("道路データの取得に失敗しました (");
        message.append(responseCode).append(", ").append(endpoint).append(", ").append(requestFormat.label).append(")");
        String compactBody = responseBody == null ? "" : responseBody.replaceAll("\\s+", " ").trim();
        if (!compactBody.isEmpty()) {
            message.append(": ").append(compactBody, 0, Math.min(120, compactBody.length()));
        }
        return new IOException(message.toString());
    }

    private byte[] readResponseBytes(InputStream stream) throws IOException {
        if (stream == null) {
            return new byte[0];
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[16_384];
        int read;
        long totalBytes = 0L;
        while ((read = stream.read(buffer)) != -1) {
            totalBytes += read;
            if (totalBytes > MAX_DOWNLOAD_BYTES) {
                throw new IOException("道路データが大きすぎるため経路探索を中止しました");
            }
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    private String buildOverpassQuery(RouteProfile profile, QueryBounds queryBounds) {
        String highwayFilter;
        if (profile == RouteProfile.MOTORWAY) {
            highwayFilter = "motorway|motorway_link";
        } else if (profile == RouteProfile.WALKING) {
            highwayFilter = "primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|living_street|road|service|track|path|footway|pedestrian|steps";
        } else {
            highwayFilter = "motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|living_street|road|service";
        }
        return String.format(
                Locale.US,
                "[out:json][timeout:25];"
                        + "("
                        + "way[\"highway\"][\"area\"!=\"yes\"][\"highway\"~\"%s\"](%.6f,%.6f,%.6f,%.6f);"
                        + "node[\"highway\"=\"motorway_junction\"](%.6f,%.6f,%.6f,%.6f);"
                        + ");"
                        + "(._;>;);"
                        + "out body;",
                highwayFilter,
                queryBounds.south,
                queryBounds.west,
                queryBounds.north,
                queryBounds.east,
                queryBounds.south,
                queryBounds.west,
                queryBounds.north,
                queryBounds.east
        );
    }

    private RoadNetwork parseRoadNetwork(RouteProfile profile, QueryBounds queryBounds, String responseBody) throws IOException {
        try {
            JSONObject responseJson = new JSONObject(responseBody);
            JSONArray elements = responseJson.optJSONArray("elements");
            if (elements == null || elements.length() == 0) {
                throw new IOException("道路データを取得できませんでした");
            }

            HashMap<Long, GeoPoint> pointByNodeId = new HashMap<>();
            HashMap<Long, JSONObject> nodeTagsById = new HashMap<>();
            List<WayDefinition> ways = new ArrayList<>();
            for (int i = 0; i < elements.length(); i++) {
                JSONObject element = elements.getJSONObject(i);
                String type = element.optString("type", "");
                if ("node".equals(type)) {
                    long nodeId = element.optLong("id", Long.MIN_VALUE);
                    double latitude = element.optDouble("lat", Double.NaN);
                    double longitude = element.optDouble("lon", Double.NaN);
                    if (nodeId == Long.MIN_VALUE || Double.isNaN(latitude) || Double.isNaN(longitude)) {
                        continue;
                    }
                    pointByNodeId.put(nodeId, new GeoPoint(latitude, longitude));
                    JSONObject tags = element.optJSONObject("tags");
                    if (tags != null) {
                        nodeTagsById.put(nodeId, tags);
                    }
                } else if ("way".equals(type)) {
                    JSONArray nodes = element.optJSONArray("nodes");
                    JSONObject tags = element.optJSONObject("tags");
                    if (nodes == null || nodes.length() < 2 || tags == null) {
                        continue;
                    }
                    ways.add(new WayDefinition(nodes, tags));
                }
            }

            HashMap<Long, GraphNode> graphNodesById = new HashMap<>();
            List<GraphNode> graphNodes = new ArrayList<>();
            List<RoadSegment> roadSegments = new ArrayList<>();

            for (WayDefinition way : ways) {
                if (!isRoutableWay(way.tags, profile)) {
                    continue;
                }

                EdgeDirection direction = resolveEdgeDirection(way.tags, profile);
                double speedMetersPerSecond = resolveSpeedMetersPerSecond(way.tags, profile);
                for (int nodeIndex = 1; nodeIndex < way.nodeIds.length(); nodeIndex++) {
                    long previousNodeId = way.nodeIds.getLong(nodeIndex - 1);
                    long currentNodeId = way.nodeIds.getLong(nodeIndex);
                    GeoPoint previousPoint = pointByNodeId.get(previousNodeId);
                    GeoPoint currentPoint = pointByNodeId.get(currentNodeId);
                    if (previousPoint == null || currentPoint == null) {
                        continue;
                    }

                    double edgeDistance = previousPoint.distanceToAsDouble(currentPoint);
                    if (Double.isNaN(edgeDistance) || edgeDistance <= 0d) {
                        continue;
                    }

                    GraphNode previousNode = getOrCreateGraphNode(graphNodesById, graphNodes, previousNodeId, previousPoint);
                    GraphNode currentNode = getOrCreateGraphNode(graphNodesById, graphNodes, currentNodeId, currentPoint);
                    double durationSeconds = edgeDistance / speedMetersPerSecond;

                    if (direction.allowsForward()) {
                        previousNode.edges.add(new GraphEdge(currentNode, edgeDistance, durationSeconds));
                    }
                    if (direction.allowsBackward()) {
                        currentNode.edges.add(new GraphEdge(previousNode, edgeDistance, durationSeconds));
                    }
                }
            }

            if (graphNodes.size() < 2) {
                throw new IOException("経路探索に使える道路データが不足しています");
            }

            List<GraphNode> interchangeNodes = new ArrayList<>();
            for (GraphNode graphNode : graphNodes) {
                JSONObject tags = nodeTagsById.get(graphNode.id);
                if (isMotorwayJunction(tags)) {
                    interchangeNodes.add(graphNode);
                }
            }

            return new RoadNetwork(
                    profile,
                    queryBounds,
                    graphNodesById,
                    graphNodes,
                    roadSegments,
                    interchangeNodes
            );
        } catch (JSONException e) {
            throw new IOException("道路データの解析に失敗しました", e);
        }
    }

    private boolean isRoutableWay(JSONObject tags, RouteProfile profile) {
        String highway = normalizeTagValue(tags.optString("highway", ""));
        if (highway.isEmpty()) {
            return false;
        }

        String area = normalizeTagValue(tags.optString("area", ""));
        if ("yes".equals(area)) {
            return false;
        }

        String access = normalizeTagValue(tags.optString("access", ""));
        if ("no".equals(access) || "private".equals(access)) {
            return false;
        }

        if (profile == RouteProfile.WALKING) {
            if (!"primary".equals(highway)
                    && !"primary_link".equals(highway)
                    && !"secondary".equals(highway)
                    && !"secondary_link".equals(highway)
                    && !"tertiary".equals(highway)
                    && !"tertiary_link".equals(highway)
                    && !"unclassified".equals(highway)
                    && !"residential".equals(highway)
                    && !"living_street".equals(highway)
                    && !"road".equals(highway)
                    && !"service".equals(highway)
                    && !"track".equals(highway)
                    && !"path".equals(highway)
                    && !"footway".equals(highway)
                    && !"pedestrian".equals(highway)
                    && !"steps".equals(highway)) {
                return false;
            }

            String foot = normalizeTagValue(tags.optString("foot", ""));
            if ("no".equals(foot) || "private".equals(foot) || "use_sidepath".equals(foot)) {
                return false;
            }

            String pedestrian = normalizeTagValue(tags.optString("pedestrian", ""));
            return !"no".equals(pedestrian) && !"private".equals(pedestrian);
        }

        if (profile == RouteProfile.MOTORWAY) {
            if (!"motorway".equals(highway) && !"motorway_link".equals(highway)) {
                return false;
            }
        } else if (!"motorway".equals(highway)
                && !"motorway_link".equals(highway)
                && !"trunk".equals(highway)
                && !"trunk_link".equals(highway)
                && !"primary".equals(highway)
                && !"primary_link".equals(highway)
                && !"secondary".equals(highway)
                && !"secondary_link".equals(highway)
                && !"tertiary".equals(highway)
                && !"tertiary_link".equals(highway)
                && !"unclassified".equals(highway)
                && !"residential".equals(highway)
                && !"living_street".equals(highway)
                && !"road".equals(highway)
                && !"service".equals(highway)) {
            return false;
        }

        String vehicle = normalizeTagValue(tags.optString("vehicle", ""));
        if ("no".equals(vehicle) || "private".equals(vehicle)) {
            return false;
        }

        String motorVehicle = normalizeTagValue(tags.optString("motor_vehicle", ""));
        if ("no".equals(motorVehicle) || "private".equals(motorVehicle)) {
            return false;
        }

        String motorcar = normalizeTagValue(tags.optString("motorcar", ""));
        return !"no".equals(motorcar) && !"private".equals(motorcar);
    }

    private boolean isMotorwayJunction(JSONObject tags) {
        if (tags == null) {
            return false;
        }
        return "motorway_junction".equals(normalizeTagValue(tags.optString("highway", "")));
    }

    private EdgeDirection resolveEdgeDirection(JSONObject tags, RouteProfile profile) {
        if (profile == RouteProfile.WALKING) {
            return EdgeDirection.BIDIRECTIONAL;
        }

        String directionTag = firstNonBlank(
                tags.optString("oneway:motor_vehicle", ""),
                tags.optString("oneway:vehicle", ""),
                tags.optString("oneway", "")
        );
        String normalizedDirection = normalizeTagValue(directionTag);
        if ("yes".equals(normalizedDirection) || "1".equals(normalizedDirection) || "true".equals(normalizedDirection)) {
            return EdgeDirection.FORWARD_ONLY;
        }
        if ("-1".equals(normalizedDirection) || "reverse".equals(normalizedDirection)) {
            return EdgeDirection.BACKWARD_ONLY;
        }
        if ("no".equals(normalizedDirection) || "0".equals(normalizedDirection) || "false".equals(normalizedDirection)) {
            return EdgeDirection.BIDIRECTIONAL;
        }

        String junction = normalizeTagValue(tags.optString("junction", ""));
        if ("roundabout".equals(junction) || "circular".equals(junction)) {
            return EdgeDirection.FORWARD_ONLY;
        }
        return EdgeDirection.BIDIRECTIONAL;
    }

    private double resolveSpeedMetersPerSecond(JSONObject tags, RouteProfile profile) {
        if (profile == RouteProfile.WALKING) {
            String highway = normalizeTagValue(tags.optString("highway", ""));
            if ("steps".equals(highway)) {
                return 0.9d;
            }
            if ("path".equals(highway) || "track".equals(highway)) {
                return 1.11d;
            }
            return DEFAULT_WALKING_SPEED_METERS_PER_SECOND;
        }

        double taggedSpeed = parseMaxSpeedMetersPerSecond(firstNonBlank(
                tags.optString("maxspeed:forward", ""),
                tags.optString("maxspeed", "")
        ));
        if (!Double.isNaN(taggedSpeed) && taggedSpeed > 0d) {
            return taggedSpeed;
        }

        String highway = normalizeTagValue(tags.optString("highway", ""));
        switch (highway) {
            case "motorway":
                return 27.78d;
            case "trunk":
                return 22.22d;
            case "motorway_link":
            case "trunk_link":
                return 13.89d;
            case "primary":
            case "primary_link":
                return 16.67d;
            case "secondary":
            case "secondary_link":
                return 13.89d;
            case "tertiary":
            case "tertiary_link":
            case "unclassified":
            case "road":
                return 11.11d;
            case "residential":
            case "service":
                return 8.33d;
            case "living_street":
                return 5.56d;
            default:
                return DEFAULT_APPROACH_SPEED_METERS_PER_SECOND;
        }
    }

    private double parseMaxSpeedMetersPerSecond(String rawValue) {
        String normalized = normalizeTagValue(rawValue);
        if (normalized.isEmpty()) {
            return Double.NaN;
        }

        Matcher matcher = SPEED_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return Double.NaN;
        }

        double numericValue;
        try {
            numericValue = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        if (numericValue <= 0d) {
            return Double.NaN;
        }

        if (normalized.contains("mph")) {
            return numericValue * 0.44704d;
        }
        return numericValue / 3.6d;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private String normalizeTagValue(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    private GraphNode getOrCreateGraphNode(
            HashMap<Long, GraphNode> graphNodesById,
            List<GraphNode> graphNodes,
            long nodeId,
            GeoPoint point
    ) {
        GraphNode existingNode = graphNodesById.get(nodeId);
        if (existingNode != null) {
            return existingNode;
        }

        GraphNode newNode = new GraphNode(nodeId, copyPoint(point));
        graphNodesById.put(nodeId, newNode);
        graphNodes.add(newNode);
        return newNode;
    }

    private static GeoPoint copyPoint(GeoPoint point) {
        return new GeoPoint(point.getLatitude(), point.getLongitude());
    }

    private enum RequestFormat {
        FORM_URLENCODED("application/x-www-form-urlencoded; charset=UTF-8", "form"),
        PLAIN_TEXT("text/plain; charset=UTF-8", "plain");

        private final String contentType;
        private final String label;

        RequestFormat(String contentType, String label) {
            this.contentType = contentType;
            this.label = label;
        }
    }

    private void addPointIfSeparated(List<GeoPoint> routePoints, GeoPoint point) {
        if (routePoints.isEmpty()) {
            routePoints.add(copyPoint(point));
            return;
        }

        GeoPoint lastPoint = routePoints.get(routePoints.size() - 1);
        if (lastPoint.distanceToAsDouble(point) >= 1d) {
            routePoints.add(copyPoint(point));
        }
    }

    public static class RouteResult {
        private final List<GeoPoint> points;
        private final double distanceMeters;
        private final double durationSeconds;
        private final List<RoadSegment> roadSegments;
        private final String summaryLabel;

        public RouteResult(
                List<GeoPoint> points,
                double distanceMeters,
                double durationSeconds,
                List<RoadSegment> roadSegments,
                String summaryLabel
        ) {
            this.points = new ArrayList<>(points);
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
            this.roadSegments = new ArrayList<>(roadSegments);
            this.summaryLabel = summaryLabel;
        }

        public List<GeoPoint> getPoints() {
            List<GeoPoint> copiedPoints = new ArrayList<>();
            for (GeoPoint point : points) {
                copiedPoints.add(copyPoint(point));
            }
            return copiedPoints;
        }

        public double getDistanceMeters() {
            return distanceMeters;
        }

        public double getDurationSeconds() {
            return durationSeconds;
        }

        public List<RoadSegment> getRoadSegments() {
            return new ArrayList<>(roadSegments);
        }

        public String getSummaryLabel() {
            return summaryLabel;
        }
    }

    public static class RoadSegment {
        private final GeoPoint startPoint;
        private final GeoPoint endPoint;

        public RoadSegment(GeoPoint startPoint, GeoPoint endPoint) {
            this.startPoint = copyPoint(startPoint);
            this.endPoint = copyPoint(endPoint);
        }

        public GeoPoint getStartPoint() {
            return copyPoint(startPoint);
        }

        public GeoPoint getEndPoint() {
            return copyPoint(endPoint);
        }
    }

    private static class WayDefinition {
        private final JSONArray nodeIds;
        private final JSONObject tags;

        private WayDefinition(JSONArray nodeIds, JSONObject tags) {
            this.nodeIds = nodeIds;
            this.tags = tags;
        }
    }

    private class RoadNetwork {
        private final RouteProfile profile;
        private final QueryBounds bounds;
        private final HashMap<Long, GraphNode> graphNodesById;
        private final List<GraphNode> graphNodes;
        private final List<RoadSegment> roadSegments;
        private final List<GraphNode> interchangeNodes;
        private final double maxRouteSpeedMetersPerSecond;

        private RoadNetwork(
                RouteProfile profile,
                QueryBounds bounds,
                HashMap<Long, GraphNode> graphNodesById,
                List<GraphNode> graphNodes,
                List<RoadSegment> roadSegments,
                List<GraphNode> interchangeNodes
        ) {
            this.profile = profile;
            this.bounds = bounds;
            this.graphNodesById = graphNodesById;
            this.graphNodes = graphNodes;
            this.roadSegments = roadSegments;
            this.interchangeNodes = interchangeNodes;
            this.maxRouteSpeedMetersPerSecond = profile == RouteProfile.WALKING
                    ? MAX_WALKING_ROUTE_SPEED_METERS_PER_SECOND
                    : MAX_ROUTE_SPEED_METERS_PER_SECOND;
        }

        private boolean covers(QueryBounds requestedBounds) {
            return bounds.covers(requestedBounds);
        }

        private List<GraphNode> getInterchangeNodes() {
            return new ArrayList<>(interchangeNodes);
        }

        private GraphNode findNodeById(long nodeId) {
            return graphNodesById.get(nodeId);
        }

        private RouteResult findRoute(GeoPoint origin, GeoPoint destination, String summaryLabel) throws IOException {
            NearestNode originNode = findNearestNode(origin);
            NearestNode destinationNode = findNearestNode(destination);
            if (originNode == null || originNode.distanceMeters > MAX_ENDPOINT_SNAP_DISTANCE_METERS) {
                throw new IOException("現在地付近の道路を取得できませんでした");
            }
            if (destinationNode == null || destinationNode.distanceMeters > MAX_ENDPOINT_SNAP_DISTANCE_METERS) {
                throw new IOException("目的地付近の道路を取得できませんでした");
            }
            return findRoute(origin, originNode.node, destination, destinationNode.node, summaryLabel);
        }

        private RouteResult findRoute(GeoPoint origin, GraphNode destinationNode, String summaryLabel) throws IOException {
            NearestNode originNode = findNearestNode(origin);
            if (originNode == null || originNode.distanceMeters > MAX_ENDPOINT_SNAP_DISTANCE_METERS) {
                throw new IOException("現在地付近の道路を取得できませんでした");
            }
            return findRoute(origin, originNode.node, destinationNode.point, destinationNode, summaryLabel);
        }

        private RouteResult findRoute(GraphNode originNode, GeoPoint destination, String summaryLabel) throws IOException {
            NearestNode destinationNode = findNearestNode(destination);
            if (destinationNode == null || destinationNode.distanceMeters > MAX_ENDPOINT_SNAP_DISTANCE_METERS) {
                throw new IOException("目的地付近の道路を取得できませんでした");
            }
            return findRoute(originNode.point, originNode, destination, destinationNode.node, summaryLabel);
        }

        private RouteResult findRoute(GraphNode originNode, GraphNode destinationNode, String summaryLabel) throws IOException {
            return findRoute(originNode.point, originNode, destinationNode.point, destinationNode, summaryLabel);
        }

        private RouteResult findRoute(
                GeoPoint routeOrigin,
                GraphNode originNode,
                GeoPoint routeDestination,
                GraphNode destinationNode,
                String summaryLabel
        ) throws IOException {
            SearchPath path = searchShortestPath(originNode, destinationNode);
            if (path.isEmpty()) {
                throw new IOException("道路に沿った経路を見つけられませんでした");
            }

            List<GeoPoint> routePoints = new ArrayList<>();
            addPointIfSeparated(routePoints, routeOrigin);
            for (GraphNode node : path.nodes) {
                addPointIfSeparated(routePoints, node.point);
            }
            addPointIfSeparated(routePoints, routeDestination);

            double routeDistanceMeters = calculateDistance(routePoints);
            double routeDurationSeconds = path.durationSeconds
                    + calculateApproachDuration(routeOrigin, originNode.point)
                    + calculateApproachDuration(destinationNode.point, routeDestination);
            return new RouteResult(
                    routePoints,
                    routeDistanceMeters,
                    routeDurationSeconds,
                    roadSegments,
                    summaryLabel
            );
        }

        private NearestNode findNearestNode(GeoPoint point) {
            GraphNode nearestNode = null;
            double nearestDistance = Double.POSITIVE_INFINITY;
            for (GraphNode candidate : graphNodes) {
                double distance = point.distanceToAsDouble(candidate.point);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestNode = candidate;
                }
            }
            if (nearestNode == null) {
                return null;
            }
            return new NearestNode(nearestNode, nearestDistance);
        }

        private SearchPath searchShortestPath(GraphNode startNode, GraphNode destinationNode) {
            HashMap<GraphNode, Double> bestScoreByNode = new HashMap<>();
            HashMap<GraphNode, GraphNode> previousNodeByNode = new HashMap<>();
            HashSet<GraphNode> visitedNodes = new HashSet<>();
            PriorityQueue<SearchState> frontier = new PriorityQueue<>(Comparator.comparingDouble(state -> state.priority));

            bestScoreByNode.put(startNode, 0d);
            frontier.add(new SearchState(startNode, estimateRemainingDurationSeconds(startNode, destinationNode)));

            while (!frontier.isEmpty()) {
                SearchState nextState = frontier.poll();
                GraphNode currentNode = nextState.node;
                if (!visitedNodes.add(currentNode)) {
                    continue;
                }
                if (currentNode == destinationNode) {
                    break;
                }

                double currentScore = bestScoreByNode.containsKey(currentNode)
                        ? bestScoreByNode.get(currentNode)
                        : Double.POSITIVE_INFINITY;
                for (GraphEdge edge : currentNode.edges) {
                    GraphNode nextNode = edge.destination;
                    if (visitedNodes.contains(nextNode)) {
                        continue;
                    }

                    double candidateScore = currentScore + edge.durationSeconds;
                    double bestScore = bestScoreByNode.containsKey(nextNode)
                            ? bestScoreByNode.get(nextNode)
                            : Double.POSITIVE_INFINITY;
                    if (candidateScore >= bestScore) {
                        continue;
                    }

                    bestScoreByNode.put(nextNode, candidateScore);
                    previousNodeByNode.put(nextNode, currentNode);
                    frontier.add(new SearchState(
                            nextNode,
                            candidateScore + estimateRemainingDurationSeconds(nextNode, destinationNode)
                    ));
                }
            }

            if (startNode != destinationNode && !previousNodeByNode.containsKey(destinationNode)) {
                return SearchPath.empty();
            }

            List<GraphNode> path = new ArrayList<>();
            GraphNode currentNode = destinationNode;
            path.add(currentNode);
            while (currentNode != startNode) {
                currentNode = previousNodeByNode.get(currentNode);
                if (currentNode == null) {
                    return SearchPath.empty();
                }
                path.add(0, currentNode);
            }
            double durationSeconds = bestScoreByNode.containsKey(destinationNode)
                    ? bestScoreByNode.get(destinationNode)
                    : 0d;
            return new SearchPath(path, durationSeconds);
        }

        private double estimateRemainingDurationSeconds(GraphNode currentNode, GraphNode destinationNode) {
            return currentNode.point.distanceToAsDouble(destinationNode.point) / maxRouteSpeedMetersPerSecond;
        }

        private double calculateApproachDuration(GeoPoint startPoint, GeoPoint endPoint) {
            return startPoint.distanceToAsDouble(endPoint) / DEFAULT_APPROACH_SPEED_METERS_PER_SECOND;
        }

        private double calculateDistance(List<GeoPoint> routePoints) {
            double totalDistance = 0d;
            for (int index = 1; index < routePoints.size(); index++) {
                totalDistance += routePoints.get(index - 1).distanceToAsDouble(routePoints.get(index));
            }
            return totalDistance;
        }
    }

    private static class InterchangeCandidate {
        private final GraphNode motorwayNode;
        private final double straightLineDistanceMeters;

        private InterchangeCandidate(GraphNode motorwayNode, double straightLineDistanceMeters) {
            this.motorwayNode = motorwayNode;
            this.straightLineDistanceMeters = straightLineDistanceMeters;
        }
    }

    private static class NearestNode {
        private final GraphNode node;
        private final double distanceMeters;

        private NearestNode(GraphNode node, double distanceMeters) {
            this.node = node;
            this.distanceMeters = distanceMeters;
        }
    }

    private static class SearchPath {
        private final List<GraphNode> nodes;
        private final double durationSeconds;

        private SearchPath(List<GraphNode> nodes, double durationSeconds) {
            this.nodes = nodes;
            this.durationSeconds = durationSeconds;
        }

        private static SearchPath empty() {
            return new SearchPath(new ArrayList<>(), Double.POSITIVE_INFINITY);
        }

        private boolean isEmpty() {
            return nodes.isEmpty();
        }
    }

    private static class SearchState {
        private final GraphNode node;
        private final double priority;

        private SearchState(GraphNode node, double priority) {
            this.node = node;
            this.priority = priority;
        }
    }

    private static class GraphEdge {
        private final GraphNode destination;
        private final double distanceMeters;
        private final double durationSeconds;

        private GraphEdge(GraphNode destination, double distanceMeters, double durationSeconds) {
            this.destination = destination;
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
        }
    }

    private static class GraphNode {
        private final long id;
        private final GeoPoint point;
        private final List<GraphEdge> edges = new ArrayList<>();

        private GraphNode(long id, GeoPoint point) {
            this.id = id;
            this.point = point;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof GraphNode)) {
                return false;
            }
            GraphNode graphNode = (GraphNode) other;
            return id == graphNode.id;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(id);
        }
    }

    private enum RouteProfile {
        DRIVING,
        WALKING,
        MOTORWAY
    }

    public enum TravelMode {
        DRIVING("自動車"),
        WALKING("歩行");

        private final String label;

        TravelMode(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private enum EdgeDirection {
        BIDIRECTIONAL(true, true),
        FORWARD_ONLY(true, false),
        BACKWARD_ONLY(false, true);

        private final boolean allowsForward;
        private final boolean allowsBackward;

        EdgeDirection(boolean allowsForward, boolean allowsBackward) {
            this.allowsForward = allowsForward;
            this.allowsBackward = allowsBackward;
        }

        private boolean allowsForward() {
            return allowsForward;
        }

        private boolean allowsBackward() {
            return allowsBackward;
        }
    }

    private static class QueryBounds {
        private static final double METERS_PER_LATITUDE_DEGREE = 111_320d;

        private final double north;
        private final double east;
        private final double south;
        private final double west;

        private QueryBounds(double north, double east, double south, double west) {
            this.north = north;
            this.east = east;
            this.south = south;
            this.west = west;
        }

        private static QueryBounds from(GeoPoint origin, GeoPoint destination) {
            double directDistance = origin.distanceToAsDouble(destination);
            double paddingMeters = clamp(directDistance * ROUTE_PADDING_RATIO, MIN_PADDING_METERS, MAX_PADDING_METERS);
            double meanLatitudeRadians = Math.toRadians((origin.getLatitude() + destination.getLatitude()) / 2d);
            double metersPerLongitudeDegree = Math.max(
                    1d,
                    METERS_PER_LATITUDE_DEGREE * Math.cos(meanLatitudeRadians)
            );

            double latitudePadding = paddingMeters / METERS_PER_LATITUDE_DEGREE;
            double longitudePadding = paddingMeters / metersPerLongitudeDegree;
            double north = Math.max(origin.getLatitude(), destination.getLatitude()) + latitudePadding;
            double south = Math.min(origin.getLatitude(), destination.getLatitude()) - latitudePadding;
            double east = Math.max(origin.getLongitude(), destination.getLongitude()) + longitudePadding;
            double west = Math.min(origin.getLongitude(), destination.getLongitude()) - longitudePadding;
            return new QueryBounds(north, east, south, west);
        }

        private boolean covers(QueryBounds other) {
            return north >= other.north
                    && east >= other.east
                    && south <= other.south
                    && west <= other.west;
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
