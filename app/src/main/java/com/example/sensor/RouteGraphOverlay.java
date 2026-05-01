package com.example.sensor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.List;

public class RouteGraphOverlay extends Overlay {

    private final Paint segmentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodeFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodeStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private RoutePlanner.RouteGraph routeGraph;
    private boolean graphVisible;

    public RouteGraphOverlay(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;

        segmentPaint.setColor(Color.parseColor("#5590CAF9"));
        segmentPaint.setStrokeWidth(dp(2f));
        segmentPaint.setStrokeCap(Paint.Cap.ROUND);

        nodeFillPaint.setColor(Color.parseColor("#B3FFFFFF"));
        nodeFillPaint.setStyle(Paint.Style.FILL);

        nodeStrokePaint.setColor(Color.parseColor("#FF4FC3F7"));
        nodeStrokePaint.setStyle(Paint.Style.STROKE);
        nodeStrokePaint.setStrokeWidth(dp(1.5f));
    }

    public void setRouteGraph(RoutePlanner.RouteGraph routeGraph) {
        this.routeGraph = routeGraph;
    }

    public void clear() {
        routeGraph = null;
    }

    public void setGraphVisible(boolean graphVisible) {
        this.graphVisible = graphVisible;
    }

    public boolean isGraphVisible() {
        return graphVisible;
    }

    @Override
    public void draw(Canvas canvas, MapView mapView, boolean shadow) {
        if (shadow || !graphVisible || routeGraph == null) {
            return;
        }

        Projection projection = mapView.getProjection();
        if (projection == null) {
            return;
        }

        List<RoutePlanner.RouteSegment> segments = routeGraph.getSegments();
        for (RoutePlanner.RouteSegment segment : segments) {
            Point startPoint = projection.toPixels(segment.getStartPoint(), null);
            Point endPoint = projection.toPixels(segment.getEndPoint(), null);
            canvas.drawLine(startPoint.x, startPoint.y, endPoint.x, endPoint.y, segmentPaint);
        }

        List<GeoPoint> nodePoints = routeGraph.getNodePoints();
        float nodeRadius = dp(3f);
        for (GeoPoint nodePoint : nodePoints) {
            Point screenPoint = projection.toPixels(nodePoint, null);
            canvas.drawCircle(screenPoint.x, screenPoint.y, nodeRadius, nodeFillPaint);
            canvas.drawCircle(screenPoint.x, screenPoint.y, nodeRadius, nodeStrokePaint);
        }
    }

    private float dp(float value) {
        return value * density;
    }
}
