package com.example.sensor;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RoutePlanner {

    private static final int MIN_ROUTE_LAYERS = 4;
    private static final int MAX_ROUTE_LAYERS = 24;
    private static final double TARGET_SEGMENT_LENGTH_METERS = 70d;
    private static final double MIN_CORRIDOR_HALF_WIDTH_METERS = 16d;
    private static final double MAX_CORRIDOR_HALF_WIDTH_METERS = 96d;
    private static final double ESTIMATED_TRAVEL_SPEED_METERS_PER_SECOND = 1.35d;
    private static final double DESTINATION_WEIGHT = 0.08d;
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
            points.add(new GeoPoint(origin.getLatitude(), origin.getLongitude()));
            points.add(new GeoPoint(destination.getLatitude(), destination.getLongitude()));
            return new RouteResult(points, Math.max(0d, directDistance), 0d);
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

        List<List<RouteNode>> routeLayers = buildRouteLayers(frame, destination, directDistance, layerCount, corridorHalfWidth);
        RouteNode startNode = new RouteNode(
                new GeoPoint(origin.getLatitude(), origin.getLongitude()),
                0d,
                0d,
                directDistance
        );
        startNode.bestScore = 0d;

        List<RouteNode> previousLayerNodes = new ArrayList<>();
        previousLayerNodes.add(startNode);

        for (List<RouteNode> layerNodes : routeLayers) {
            for (RouteNode node : layerNodes) {
                connectBestPrevious(previousLayerNodes, node);
            }
            previousLayerNodes = layerNodes;
        }

        RouteNode destinationNode = new RouteNode(
                new GeoPoint(destination.getLatitude(), destination.getLongitude()),
                directDistance,
                0d,
                0d
        );
        connectBestPrevious(previousLayerNodes, destinationNode);

        List<GeoPoint> routePoints = rebuildPath(destinationNode);
        double routeDistanceMeters = calculateTotalDistance(routePoints);
        double durationSeconds = routeDistanceMeters / ESTIMATED_TRAVEL_SPEED_METERS_PER_SECOND;
        return new RouteResult(routePoints, routeDistanceMeters, durationSeconds);
    }

    private List<List<RouteNode>> buildRouteLayers(
            LocalFrame frame,
            GeoPoint destination,
            double directDistance,
            int layerCount,
            double corridorHalfWidth
    ) {
        List<List<RouteNode>> layers = new ArrayList<>();
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
                        point,
                        progressMeters,
                        lateralOffsetMeters,
                        point.distanceToAsDouble(destination)
                ));
            }
            layers.add(layerNodes);
        }
        return layers;
    }

    private void connectBestPrevious(List<RouteNode> previousLayerNodes, RouteNode currentNode) {
        for (RouteNode previousNode : previousLayerNodes) {
            double score = calculateTransitionScore(previousNode, currentNode);
            if (score < currentNode.bestScore) {
                currentNode.bestScore = score;
                currentNode.previousNode = previousNode;
            }
        }
    }

    private double calculateTransitionScore(RouteNode previousNode, RouteNode currentNode) {
        double progressDelta = currentNode.progressMeters - previousNode.progressMeters;
        if (progressDelta <= 0d) {
            return Double.POSITIVE_INFINITY;
        }
        double segmentDistance = previousNode.point.distanceToAsDouble(currentNode.point);
        double continuityPenalty = Math.abs(segmentDistance - progressDelta) * 0.18d;
        double destinationBias = currentNode.remainingDistanceMeters * DESTINATION_WEIGHT;
        double deviationPenalty = Math.abs(currentNode.lateralOffsetMeters) * DEVIATION_WEIGHT;
        double laneChangePenalty = Math.abs(currentNode.lateralOffsetMeters - previousNode.lateralOffsetMeters)
                * LANE_CHANGE_WEIGHT;
        return previousNode.bestScore
                + segmentDistance
                + continuityPenalty
                + destinationBias
                + deviationPenalty
                + laneChangePenalty;
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
                normalizedPoints.add(point);
                continue;
            }

            GeoPoint lastPoint = normalizedPoints.get(normalizedPoints.size() - 1);
            if (lastPoint.distanceToAsDouble(point) >= 1d) {
                normalizedPoints.add(point);
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

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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

    private static class RouteNode {
        private final GeoPoint point;
        private final double progressMeters;
        private final double lateralOffsetMeters;
        private final double remainingDistanceMeters;
        private double bestScore = Double.POSITIVE_INFINITY;
        private RouteNode previousNode;

        private RouteNode(
                GeoPoint point,
                double progressMeters,
                double lateralOffsetMeters,
                double remainingDistanceMeters
        ) {
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
                    new GeoPoint(origin.getLatitude(), origin.getLongitude()),
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
