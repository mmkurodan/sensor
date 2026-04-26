package com.example.sensor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.Locale;

public class CoordinateGridOverlay extends Overlay {

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public CoordinateGridOverlay(Context context) {
        density = context.getResources().getDisplayMetrics().density;

        linePaint.setColor(Color.parseColor("#55B0BEC5"));
        linePaint.setStrokeWidth(dp(1f));

        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(dp(10f));

        labelBackgroundPaint.setColor(Color.parseColor("#AA102027"));
        labelBackgroundPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void draw(Canvas canvas, MapView mapView, boolean shadow) {
        if (shadow) {
            return;
        }

        BoundingBox boundingBox = mapView.getBoundingBox();
        if (boundingBox == null) {
            return;
        }

        double north = boundingBox.getLatNorth();
        double south = boundingBox.getLatSouth();
        double west = boundingBox.getLonWest();
        double east = boundingBox.getLonEast();
        double step = chooseStep(Math.max(Math.abs(north - south), Math.abs(east - west)));

        Projection projection = mapView.getProjection();

        for (double latitude = floorToStep(south, step); latitude <= north; latitude += step) {
            Point start = projection.toPixels(new GeoPoint(latitude, west), null);
            Point end = projection.toPixels(new GeoPoint(latitude, east), null);
            canvas.drawLine(0, start.y, canvas.getWidth(), end.y, linePaint);
            drawLabel(canvas, formatLabel(latitude, true), dp(8f), start.y - dp(4f));
        }

        for (double longitude = floorToStep(west, step); longitude <= east; longitude += step) {
            Point start = projection.toPixels(new GeoPoint(north, longitude), null);
            Point end = projection.toPixels(new GeoPoint(south, longitude), null);
            canvas.drawLine(start.x, 0, end.x, canvas.getHeight(), linePaint);
            drawLabel(canvas, formatLabel(longitude, false), start.x + dp(4f), dp(20f));
        }
    }

    private void drawLabel(Canvas canvas, String text, float x, float y) {
        float textWidth = labelPaint.measureText(text);
        float textHeight = labelPaint.getTextSize();
        float left = x - dp(4f);
        float top = y - textHeight;
        float right = x + textWidth + dp(4f);
        float bottom = y + dp(4f);
        canvas.drawRoundRect(left, top, right, bottom, dp(4f), dp(4f), labelBackgroundPaint);
        canvas.drawText(text, x, y, labelPaint);
    }

    private String formatLabel(double value, boolean latitude) {
        String suffix;
        if (latitude) {
            suffix = value >= 0 ? "N" : "S";
        } else {
            suffix = value >= 0 ? "E" : "W";
        }
        return String.format(Locale.US, "%.3f°%s", Math.abs(value), suffix);
    }

    private double chooseStep(double span) {
        double[] steps = {20d, 10d, 5d, 2d, 1d, 0.5d, 0.25d, 0.1d, 0.05d, 0.02d, 0.01d, 0.005d};
        for (double step : steps) {
            if (span / step <= 8d) {
                return step;
            }
        }
        return steps[steps.length - 1];
    }

    private double floorToStep(double value, double step) {
        return Math.floor(value / step) * step;
    }

    private float dp(float value) {
        return value * density;
    }
}
