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

public class RoadNetworkRouter {

    private static final String OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter";
    private static final double ESTIMATED_TRAVEL_SPEED_METERS_PER_SECOND = 1.35d;
    private static final double ROUTE_PADDING_RATIO = 0.20d;
    private static final double MIN_PADDING_METERS = 120d;
    private static final double MAX_PADDING_METERS = 5_000d;
    private static final double MAX_ENDPOINT_SNAP_DISTANCE_METERS = 400d;
    private static final long MAX_DOWNLOAD_BYTES = 5L * 1024L * 1024L * 1024L;

    private final String userAgent;

    private RoadNetwork cachedNetwork;

    public RoadNetworkRouter(Context context) {
        userAgent = context.getApplicationContext().getPackageName();
    }

    public synchronized RouteResult findRoute(GeoPoint origin, GeoPoint destination) throws IOException {
        if (origin == null || destination == null) {
            throw new IllegalArgumentException("経路の始点または終点がありません");
        }

        QueryBounds queryBounds = QueryBounds.from(origin, destination);
        RoadNetwork network = getRoadNetwork(queryBounds);
        return network.findRoute(origin, destination);
    }

    public synchronized void clearCache() {
        cachedNetwork = null;
    }

    private RoadNetwork getRoadNetwork(QueryBounds queryBounds) throws IOException {
        if (cachedNetwork != null && cachedNetwork.covers(queryBounds)) {
            return cachedNetwork;
        }

        RoadNetwork downloadedNetwork = downloadRoadNetwork(queryBounds);
        cachedNetwork = downloadedNetwork;
        return downloadedNetwork;
    }

    private RoadNetwork downloadRoadNetwork(QueryBounds queryBounds) throws IOException {
        HttpURLConnection connection = null;
        try {
            String query = buildOverpassQuery(queryBounds);
            byte[] requestBody = ("data=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name()))
                    .getBytes(StandardCharsets.UTF_8);

            connection = (HttpURLConnection) new URL(OVERPASS_ENDPOINT).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(25_000);
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setFixedLengthStreamingMode(requestBody.length);

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(requestBody);
            }

            int responseCode = connection.getResponseCode();
            InputStream responseStream = responseCode >= 200 && responseCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            byte[] responseBytes = readResponseBytes(responseStream);

            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("道路データの取得に失敗しました (" + responseCode + ")");
            }

            return parseRoadNetwork(queryBounds, new String(responseBytes, StandardCharsets.UTF_8));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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
                throw new IOException("道路データが 5GB を超えるため適用しません");
            }
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    private String buildOverpassQuery(QueryBounds queryBounds) {
        return String.format(
                Locale.US,
                "[out:json][timeout:25];"
                        + "("
                        + "way[\"highway\"][\"area\"!=\"yes\"]"
                        + "[\"highway\"!~\"motorway|motorway_link|trunk|trunk_link|raceway|construction|proposed|escape\"]"
                        + "(%.6f,%.6f,%.6f,%.6f);"
                        + ");"
                        + "(._;>;);"
                        + "out body;",
                queryBounds.south,
                queryBounds.west,
                queryBounds.north,
                queryBounds.east
        );
    }

    private RoadNetwork parseRoadNetwork(QueryBounds queryBounds, String responseBody) throws IOException {
        try {
            JSONObject responseJson = new JSONObject(responseBody);
            JSONArray elements = responseJson.optJSONArray("elements");
            if (elements == null || elements.length() == 0) {
                throw new IOException("道路データを取得できませんでした");
            }

            HashMap<Long, GeoPoint> pointByNodeId = new HashMap<>();
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
            HashSet<String> segmentKeys = new HashSet<>();
            for (WayDefinition way : ways) {
                if (!isWalkableWay(way.tags)) {
                    continue;
                }

                boolean oneWayForWalking = isOneWayForWalking(way.tags);
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
                    previousNode.edges.add(new GraphEdge(currentNode, edgeDistance));
                    if (!oneWayForWalking) {
                        currentNode.edges.add(new GraphEdge(previousNode, edgeDistance));
                    }

                    String segmentKey = previousNodeId < currentNodeId
                            ? previousNodeId + ":" + currentNodeId
                            : currentNodeId + ":" + previousNodeId;
                    if (segmentKeys.add(segmentKey)) {
                        roadSegments.add(new RoadSegment(previousPoint, currentPoint));
                    }
                }
            }

            if (graphNodes.size() < 2 || roadSegments.isEmpty()) {
                throw new IOException("経路探索に使える道路データが不足しています");
            }

            return new RoadNetwork(queryBounds, graphNodes, roadSegments);
        } catch (JSONException e) {
            throw new IOException("道路データの解析に失敗しました", e);
        }
    }

    private boolean isWalkableWay(JSONObject tags) {
        String highway = tags.optString("highway", "");
        if (highway.isEmpty()) {
            return false;
        }

        String area = tags.optString("area", "");
        if ("yes".equals(area)) {
            return false;
        }

        String access = tags.optString("access", "");
        if ("no".equals(access) || "private".equals(access)) {
            return false;
        }

        String foot = tags.optString("foot", "");
        if ("no".equals(foot) || "private".equals(foot)) {
            return false;
        }

        return true;
    }

    private boolean isOneWayForWalking(JSONObject tags) {
        String footOneway = tags.optString("oneway:foot", "");
        if ("yes".equals(footOneway) || "1".equals(footOneway) || "true".equals(footOneway)) {
            return true;
        }

        String foot = tags.optString("foot", "");
        if ("yes".equals(foot) || "designated".equals(foot) || "permissive".equals(foot)) {
            return false;
        }

        String oneway = tags.optString("oneway", "");
        return "yes".equals(oneway) || "1".equals(oneway) || "true".equals(oneway);
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

    public static class RouteResult {
        private final List<GeoPoint> points;
        private final double distanceMeters;
        private final double durationSeconds;
        private final List<RoadSegment> roadSegments;

        public RouteResult(
                List<GeoPoint> points,
                double distanceMeters,
                double durationSeconds,
                List<RoadSegment> roadSegments
        ) {
            this.points = new ArrayList<>(points);
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
            this.roadSegments = new ArrayList<>(roadSegments);
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

    private static class RoadNetwork {
        private final QueryBounds bounds;
        private final List<GraphNode> graphNodes;
        private final List<RoadSegment> roadSegments;

        private RoadNetwork(QueryBounds bounds, List<GraphNode> graphNodes, List<RoadSegment> roadSegments) {
            this.bounds = bounds;
            this.graphNodes = graphNodes;
            this.roadSegments = roadSegments;
        }

        private boolean covers(QueryBounds requestedBounds) {
            return bounds.covers(requestedBounds);
        }

        private RouteResult findRoute(GeoPoint origin, GeoPoint destination) throws IOException {
            NearestNode originNode = findNearestNode(origin);
            NearestNode destinationNode = findNearestNode(destination);

            if (originNode == null || originNode.distanceMeters > MAX_ENDPOINT_SNAP_DISTANCE_METERS) {
                throw new IOException("現在地付近の道路を取得できませんでした");
            }
            if (destinationNode == null || destinationNode.distanceMeters > MAX_ENDPOINT_SNAP_DISTANCE_METERS) {
                throw new IOException("目的地付近の道路を取得できませんでした");
            }

            List<GraphNode> nodePath = searchShortestPath(originNode.node, destinationNode.node);
            if (nodePath.isEmpty()) {
                throw new IOException("道路に沿った経路を見つけられませんでした");
            }

            List<GeoPoint> routePoints = new ArrayList<>();
            addPointIfSeparated(routePoints, origin);
            for (GraphNode node : nodePath) {
                addPointIfSeparated(routePoints, node.point);
            }
            addPointIfSeparated(routePoints, destination);

            double routeDistanceMeters = calculateDistance(routePoints);
            double durationSeconds = routeDistanceMeters / ESTIMATED_TRAVEL_SPEED_METERS_PER_SECOND;
            return new RouteResult(routePoints, routeDistanceMeters, durationSeconds, roadSegments);
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

        private List<GraphNode> searchShortestPath(GraphNode startNode, GraphNode destinationNode) {
            HashMap<GraphNode, Double> bestScoreByNode = new HashMap<>();
            HashMap<GraphNode, GraphNode> previousNodeByNode = new HashMap<>();
            HashSet<GraphNode> visitedNodes = new HashSet<>();
            PriorityQueue<SearchState> frontier = new PriorityQueue<>(Comparator.comparingDouble(state -> state.priority));

            bestScoreByNode.put(startNode, 0d);
            frontier.add(new SearchState(startNode, startNode.point.distanceToAsDouble(destinationNode.point)));

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

                    double candidateScore = currentScore + edge.distanceMeters;
                    double bestScore = bestScoreByNode.containsKey(nextNode)
                            ? bestScoreByNode.get(nextNode)
                            : Double.POSITIVE_INFINITY;
                    if (candidateScore >= bestScore) {
                        continue;
                    }

                    bestScoreByNode.put(nextNode, candidateScore);
                    previousNodeByNode.put(nextNode, currentNode);
                    double heuristic = nextNode.point.distanceToAsDouble(destinationNode.point);
                    frontier.add(new SearchState(nextNode, candidateScore + heuristic));
                }
            }

            if (startNode != destinationNode && !previousNodeByNode.containsKey(destinationNode)) {
                return new ArrayList<>();
            }

            List<GraphNode> path = new ArrayList<>();
            GraphNode currentNode = destinationNode;
            path.add(currentNode);
            while (currentNode != startNode) {
                currentNode = previousNodeByNode.get(currentNode);
                if (currentNode == null) {
                    return new ArrayList<>();
                }
                path.add(0, currentNode);
            }
            return path;
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

        private double calculateDistance(List<GeoPoint> routePoints) {
            double totalDistance = 0d;
            for (int index = 1; index < routePoints.size(); index++) {
                totalDistance += routePoints.get(index - 1).distanceToAsDouble(routePoints.get(index));
            }
            return totalDistance;
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

        private GraphEdge(GraphNode destination, double distanceMeters) {
            this.destination = destination;
            this.distanceMeters = distanceMeters;
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
