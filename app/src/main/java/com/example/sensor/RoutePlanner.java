package com.example.sensor;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public class RoutePlanner {

    private static final int MIN_ROUTE_LAYERS = 4;
    private static final int MAX_ROUTE_LAYERS = 24;
    private static final double TARGET_SEGMENT_LENGTH_METERS = 70d;
    private static final double MIN_CORRIDOR_HALF_WIDTH_METERS = 16d;
    private static final double MAX_CORRIDOR_HALF_WIDTH_METERS = 96d;
    private static final double ESTIMATED_TRAVEL_SPEED_METERS_PER_SECOND = 1.35d;
    private static final double DEVIATION_WEIGHT = 0.12d;
    private static final double LANE_CHANGE_WEIGHT = 0.20d;
    private static final double[] LANE_MULTIPLIERS = {-1.6d, -0.8d, 0d, 0.8d, 1.6d};

    public RouteResult findRoute(GeoPoint origin, GeoPoint destination) {
        if (origin == null || destination == null) {
            throw new IllegalArgumentException("経路の始点または終点がありません");
        }

        double directDistance = origin.distanceToAsDouble(destination);
        if (Double.isNaN(directDistance) || directDistance <= 1d) {
            List<GeoPoint> points = new ArrayList<>();
            points.add(copyPoint(origin));
            points.add(copyPoint(destination));
            return new RouteResult(
                    points,
                    Math.max(0d, directDistance),
                    0d,
                    RouteGraph.createLinear(copyPoint(origin), copyPoint(destination))
            );
        }

        LocalFrame frame = LocalFrame.create(origin, destination);
        int layerCount = clampInt(
                (int) Math.ceil(directDistance / TARGET_SEGMENT_LENGTH_METERS),
                MIN_ROUTE_LAYERS,
                MAX_ROUTE_LAYERS
        );
        double corridorHalfWidth = clamp(
                directDistance * 0.14d,
                MIN_CORRIDOR_HALF_WIDTH_METERS,
                MAX_CORRIDOR_HALF_WIDTH_METERS
        );

        GraphBundle graphBundle = buildRouteGraph(origin, destination, frame, directDistance, layerCount, corridorHalfWidth);
        List<GeoPoint> routePoints = searchShortestPath(graphBundle.startNode, graphBundle.destinationNode);
        double routeDistanceMeters = calculateTotalDistance(routePoints);
        double durationSeconds = routeDistanceMeters / ESTIMATED_TRAVEL_SPEED_METERS_PER_SECOND;
        return new RouteResult(routePoints, routeDistanceMeters, durationSeconds, graphBundle.routeGraph);
    }

    private GraphBundle buildRouteGraph(
            GeoPoint origin,
            GeoPoint destination,
            LocalFrame frame,
            double directDistance,
            int layerCount,
            double corridorHalfWidth
    ) {
        int nextNodeId = 0;
        List<List<RouteNode>> layers = new ArrayList<>();

        List<RouteNode> startLayer = new ArrayList<>();
        RouteNode startNode = new RouteNode(nextNodeId++, copyPoint(origin), 0d, 0d, directDistance);
        startLayer.add(startNode);
        layers.add(startLayer);

        for (int layerIndex = 1; layerIndex < layerCount; layerIndex++) {
            double progressRatio = layerIndex / (double) layerCount;
            double progressMeters = directDistance * progressRatio;
            double envelope = Math.sin(Math.PI * progressRatio);

            List<RouteNode> layerNodes = new ArrayList<>();
            boolean centerLaneAdded = false;
            for (double multiplier : LANE_MULTIPLIERS) {
                double lateralOffsetMeters = corridorHalfWidth * envelope * multiplier;
                if (Math.abs(lateralOffsetMeters) < 1d) {
                    if (centerLaneAdded) {
                        continue;
                    }
                    lateralOffsetMeters = 0d;
                    centerLaneAdded = true;
                }

                GeoPoint point = frame.toGeoPoint(progressMeters, lateralOffsetMeters);
                layerNodes.add(new RouteNode(
                        nextNodeId++,
                        point,
                        progressMeters,
                        lateralOffsetMeters,
                        point.distanceToAsDouble(destination)
                ));
            }
            layers.add(layerNodes);
        }

        List<RouteNode> destinationLayer = new ArrayList<>();
        RouteNode destinationNode = new RouteNode(nextNodeId++, copyPoint(destination), directDistance, 0d, 0d);
        destinationLayer.add(destinationNode);
        layers.add(destinationLayer);

        connectGraphLayers(layers);
        return new GraphBundle(startNode, destinationNode, createRouteGraph(layers));
    }

    private void connectGraphLayers(List<List<RouteNode>> layers) {
        for (int layerIndex = 0; layerIndex < layers.size() - 1; layerIndex++) {
            connectForwardEdges(layers.get(layerIndex), layers.get(layerIndex + 1));
        }

        for (int layerIndex = 1; layerIndex < layers.size() - 1; layerIndex++) {
            connectLateralEdges(layers.get(layerIndex));
        }
    }

    private void connectForwardEdges(List<RouteNode> currentLayer, List<RouteNode> nextLayer) {
        for (int currentIndex = 0; currentIndex < currentLayer.size(); currentIndex++) {
            RouteNode currentNode = currentLayer.get(currentIndex);
            for (int nextIndex = 0; nextIndex < nextLayer.size(); nextIndex++) {
                if (currentLayer.size() > 1 && nextLayer.size() > 1 && Math.abs(currentIndex - nextIndex) > 1) {
                    continue;
                }
                connectBidirectional(currentNode, nextLayer.get(nextIndex));
            }
        }
    }

    private void connectLateralEdges(List<RouteNode> layerNodes) {
        for (int nodeIndex = 1; nodeIndex < layerNodes.size(); nodeIndex++) {
            connectBidirectional(layerNodes.get(nodeIndex - 1), layerNodes.get(nodeIndex));
        }
    }

    private void connectBidirectional(RouteNode firstNode, RouteNode secondNode) {
        double traversalCost = calculateTraversalCost(firstNode, secondNode);
        firstNode.edges.add(new RouteEdge(secondNode, traversalCost));
        secondNode.edges.add(new RouteEdge(firstNode, traversalCost));
    }

    private double calculateTraversalCost(RouteNode fromNode, RouteNode toNode) {
        double segmentDistance = fromNode.point.distanceToAsDouble(toNode.point);
        double averageDeviationMeters = (Math.abs(fromNode.lateralOffsetMeters) + Math.abs(toNode.lateralOffsetMeters)) * 0.5d;
        double laneChangePenalty = Math.abs(toNode.lateralOffsetMeters - fromNode.lateralOffsetMeters) * LANE_CHANGE_WEIGHT;
        double deviationPenalty = averageDeviationMeters * DEVIATION_WEIGHT;
        return segmentDistance + deviationPenalty + laneChangePenalty;
    }

    private List<GeoPoint> searchShortestPath(RouteNode startNode, RouteNode destinationNode) {
        startNode.bestScore = 0d;

        PriorityQueue<SearchState> frontier = new PriorityQueue<>(Comparator.comparingDouble(state -> state.priority));
        frontier.add(new SearchState(startNode, destinationNode.remainingDistanceMeters));

        while (!frontier.isEmpty()) {
            RouteNode currentNode = frontier.poll().node;
            if (currentNode.visited) {
                continue;
            }
            currentNode.visited = true;
            if (currentNode == destinationNode) {
                break;
            }

            for (RouteEdge edge : currentNode.edges) {
                RouteNode nextNode = edge.destination;
                if (nextNode.visited) {
                    continue;
                }

                double candidateScore = currentNode.bestScore + edge.cost;
                if (candidateScore >= nextNode.bestScore) {
                    continue;
                }

                nextNode.bestScore = candidateScore;
                nextNode.previousNode = currentNode;
                frontier.add(new SearchState(nextNode, candidateScore + nextNode.remainingDistanceMeters));
            }
        }

        return rebuildPath(destinationNode);
    }

    private RouteGraph createRouteGraph(List<List<RouteNode>> layers) {
        List<GeoPoint> nodePoints = new ArrayList<>();
        List<RouteSegment> segments = new ArrayList<>();

        for (List<RouteNode> layerNodes : layers) {
            for (RouteNode node : layerNodes) {
                nodePoints.add(copyPoint(node.point));
                for (RouteEdge edge : node.edges) {
                    if (node.id < edge.destination.id) {
                        segments.add(new RouteSegment(copyPoint(node.point), copyPoint(edge.destination.point)));
                    }
                }
            }
        }

        return new RouteGraph(nodePoints, segments);
    }

    private List<GeoPoint> rebuildPath(RouteNode destinationNode) {
        List<GeoPoint> points = new ArrayList<>();
        RouteNode node = destinationNode;
        while (node != null) {
            points.add(node.point);
            node = node.previousNode;
        }
        Collections.reverse(points);

        List<GeoPoint> normalizedPoints = new ArrayList<>();
        for (GeoPoint point : points) {
            if (normalizedPoints.isEmpty()) {
                normalizedPoints.add(copyPoint(point));
                continue;
            }

            GeoPoint lastPoint = normalizedPoints.get(normalizedPoints.size() - 1);
            if (lastPoint.distanceToAsDouble(point) >= 1d) {
                normalizedPoints.add(copyPoint(point));
            }
        }
        return normalizedPoints;
    }

    private double calculateTotalDistance(List<GeoPoint> points) {
        double totalDistance = 0d;
        for (int i = 1; i < points.size(); i++) {
            totalDistance += points.get(i - 1).distanceToAsDouble(points.get(i));
        }
        return totalDistance;
    }

    private static GeoPoint copyPoint(GeoPoint point) {
        return new GeoPoint(point.getLatitude(), point.getLongitude());
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static class RouteResult {
        private final List<GeoPoint> points;
        private final double distanceMeters;
        private final double durationSeconds;
        private final RouteGraph routeGraph;

        public RouteResult(List<GeoPoint> points, double distanceMeters, double durationSeconds, RouteGraph routeGraph) {
            this.points = new ArrayList<>(points);
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
            this.routeGraph = routeGraph;
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

        public RouteGraph getRouteGraph() {
            return routeGraph;
        }
    }

    public static class RouteGraph {
        private final List<GeoPoint> nodePoints;
        private final List<RouteSegment> segments;

        public RouteGraph(List<GeoPoint> nodePoints, List<RouteSegment> segments) {
            this.nodePoints = new ArrayList<>(nodePoints);
            this.segments = new ArrayList<>(segments);
        }

        private static RouteGraph createLinear(GeoPoint origin, GeoPoint destination) {
            List<GeoPoint> nodePoints = new ArrayList<>();
            nodePoints.add(origin);
            nodePoints.add(destination);

            List<RouteSegment> segments = new ArrayList<>();
            segments.add(new RouteSegment(origin, destination));
            return new RouteGraph(nodePoints, segments);
        }

        public List<GeoPoint> getNodePoints() {
            List<GeoPoint> points = new ArrayList<>();
            for (GeoPoint point : nodePoints) {
                points.add(copyPoint(point));
            }
            return points;
        }

        public List<RouteSegment> getSegments() {
            return new ArrayList<>(segments);
        }
    }

    public static class RouteSegment {
        private final GeoPoint startPoint;
        private final GeoPoint endPoint;

        public RouteSegment(GeoPoint startPoint, GeoPoint endPoint) {
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

    private static class GraphBundle {
        private final RouteNode startNode;
        private final RouteNode destinationNode;
        private final RouteGraph routeGraph;

        private GraphBundle(RouteNode startNode, RouteNode destinationNode, RouteGraph routeGraph) {
            this.startNode = startNode;
            this.destinationNode = destinationNode;
            this.routeGraph = routeGraph;
        }
    }

    private static class SearchState {
        private final RouteNode node;
        private final double priority;

        private SearchState(RouteNode node, double priority) {
            this.node = node;
            this.priority = priority;
        }
    }

    private static class RouteEdge {
        private final RouteNode destination;
        private final double cost;

        private RouteEdge(RouteNode destination, double cost) {
            this.destination = destination;
            this.cost = cost;
        }
    }

    private static class RouteNode {
        private final int id;
        private final GeoPoint point;
        private final double progressMeters;
        private final double lateralOffsetMeters;
        private final double remainingDistanceMeters;
        private final List<RouteEdge> edges = new ArrayList<>();
        private double bestScore = Double.POSITIVE_INFINITY;
        private boolean visited;
        private RouteNode previousNode;

        private RouteNode(
                int id,
                GeoPoint point,
                double progressMeters,
                double lateralOffsetMeters,
                double remainingDistanceMeters
        ) {
            this.id = id;
            this.point = point;
            this.progressMeters = progressMeters;
            this.lateralOffsetMeters = lateralOffsetMeters;
            this.remainingDistanceMeters = remainingDistanceMeters;
        }
    }

    private static class LocalFrame {
        private static final double METERS_PER_LATITUDE_DEGREE = 111_320d;

        private final GeoPoint origin;
        private final double metersPerLongitudeDegree;
        private final double forwardUnitX;
        private final double forwardUnitY;
        private final double sideUnitX;
        private final double sideUnitY;

        private LocalFrame(
                GeoPoint origin,
                double metersPerLongitudeDegree,
                double forwardUnitX,
                double forwardUnitY,
                double sideUnitX,
                double sideUnitY
        ) {
            this.origin = origin;
            this.metersPerLongitudeDegree = metersPerLongitudeDegree;
            this.forwardUnitX = forwardUnitX;
            this.forwardUnitY = forwardUnitY;
            this.sideUnitX = sideUnitX;
            this.sideUnitY = sideUnitY;
        }

        private static LocalFrame create(GeoPoint origin, GeoPoint destination) {
            double meanLatitudeRadians = Math.toRadians((origin.getLatitude() + destination.getLatitude()) / 2d);
            double metersPerLongitudeDegree = Math.max(
                    1d,
                    METERS_PER_LATITUDE_DEGREE * Math.cos(meanLatitudeRadians)
            );
            double deltaX = (destination.getLongitude() - origin.getLongitude()) * metersPerLongitudeDegree;
            double deltaY = (destination.getLatitude() - origin.getLatitude()) * METERS_PER_LATITUDE_DEGREE;
            double distance = Math.hypot(deltaX, deltaY);

            double forwardUnitX = distance > 0d ? deltaX / distance : 1d;
            double forwardUnitY = distance > 0d ? deltaY / distance : 0d;
            double sideUnitX = -forwardUnitY;
            double sideUnitY = forwardUnitX;

            return new LocalFrame(
                    copyPoint(origin),
                    metersPerLongitudeDegree,
                    forwardUnitX,
                    forwardUnitY,
                    sideUnitX,
                    sideUnitY
            );
        }

        private GeoPoint toGeoPoint(double progressMeters, double lateralOffsetMeters) {
            double xMeters = (forwardUnitX * progressMeters) + (sideUnitX * lateralOffsetMeters);
            double yMeters = (forwardUnitY * progressMeters) + (sideUnitY * lateralOffsetMeters);

            double latitude = origin.getLatitude() + (yMeters / METERS_PER_LATITUDE_DEGREE);
            double longitude = origin.getLongitude() + (xMeters / metersPerLongitudeDegree);
            return new GeoPoint(latitude, longitude);
        }
    }
}
