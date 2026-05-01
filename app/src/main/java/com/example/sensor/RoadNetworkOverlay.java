package com.example.sensor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;

import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;
import java.util.List;

public class RoadNetworkOverlay extends Overlay {

    private final Paint roadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private List<RoadNetworkRouter.RoadSegment> roadSegments = new ArrayList<>();
    private boolean roadsVisible;

    public RoadNetworkOverlay(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;

        roadPaint.setColor(Color.parseColor("#6674B9FF"));
        roadPaint.setStrokeWidth(dp(2f));
        roadPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setRoadSegments(List<RoadNetworkRouter.RoadSegment> roadSegments) {
        if (roadSegments == null) {
            this.roadSegments = new ArrayList<>();
            return;
        }
        this.roadSegments = new ArrayList<>(roadSegments);
    }

    public void clear() {
        roadSegments = new ArrayList<>();
    }

    public void setRoadsVisible(boolean roadsVisible) {
        this.roadsVisible = roadsVisible;
    }

    public boolean isRoadsVisible() {
        return roadsVisible;
    }

    @Override
    public void draw(Canvas canvas, MapView mapView, boolean shadow) {
        if (shadow || !roadsVisible || roadSegments.isEmpty()) {
            return;
        }

        Projection projection = mapView.getProjection();
        if (projection == null) {
            return;
        }

        for (RoadNetworkRouter.RoadSegment segment : roadSegments) {
            Point startPoint = projection.toPixels(segment.getStartPoint(), null);
            Point endPoint = projection.toPixels(segment.getEndPoint(), null);
            canvas.drawLine(startPoint.x, startPoint.y, endPoint.x, endPoint.y, roadPaint);
        }
    }

    private float dp(float value) {
        return value * density;
    }
}
