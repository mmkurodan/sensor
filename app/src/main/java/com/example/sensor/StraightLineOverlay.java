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

public class StraightLineOverlay extends Overlay {

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private GeoPoint startPoint;
    private GeoPoint endPoint;
    private String distanceLabel;

    public StraightLineOverlay(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;

        linePaint.setColor(Color.parseColor("#4FC3F7"));
        linePaint.setStrokeWidth(dp(4f));

        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(dp(12f));
        labelPaint.setFakeBoldText(true);

        labelBackgroundPaint.setColor(Color.parseColor("#CC102027"));
        labelBackgroundPaint.setStyle(Paint.Style.FILL);
    }

    public void setLine(GeoPoint start, GeoPoint end, String label) {
        if (start == null || end == null) {
            clear();
            return;
        }

        startPoint = new GeoPoint(start.getLatitude(), start.getLongitude());
        endPoint = new GeoPoint(end.getLatitude(), end.getLongitude());
        distanceLabel = label;
    }

    public void clear() {
        startPoint = null;
        endPoint = null;
        distanceLabel = null;
    }

    @Override
    public void draw(Canvas canvas, MapView mapView, boolean shadow) {
        if (shadow || startPoint == null || endPoint == null) {
            return;
        }

        Projection projection = mapView.getProjection();
        if (projection == null) {
            return;
        }

        Point start = projection.toPixels(startPoint, null);
        Point end = projection.toPixels(endPoint, null);
        canvas.drawLine(start.x, start.y, end.x, end.y, linePaint);

        if (distanceLabel != null && !distanceLabel.isEmpty()) {
            drawDistanceLabel(canvas, start, end, distanceLabel);
        }
    }

    private void drawDistanceLabel(Canvas canvas, Point start, Point end, String text) {
        float horizontalPadding = dp(8f);
        float verticalPadding = dp(5f);
        float textWidth = labelPaint.measureText(text);
        float labelWidth = textWidth + horizontalPadding * 2f;
        float labelHeight = labelPaint.getFontSpacing() + verticalPadding * 2f;
        float edgePadding = dp(8f);

        float centerX = clamp(
                (start.x + end.x) / 2f,
                edgePadding + labelWidth / 2f,
                canvas.getWidth() - edgePadding - labelWidth / 2f
        );
        float centerY = clamp(
                (start.y + end.y) / 2f,
                edgePadding + labelHeight / 2f,
                canvas.getHeight() - edgePadding - labelHeight / 2f
        );

        float left = centerX - labelWidth / 2f;
        float top = centerY - labelHeight / 2f;
        float right = centerX + labelWidth / 2f;
        float bottom = centerY + labelHeight / 2f;

        canvas.drawRoundRect(left, top, right, bottom, dp(10f), dp(10f), labelBackgroundPaint);
        float baseline = centerY - (labelPaint.ascent() + labelPaint.descent()) / 2f;
        canvas.drawText(text, centerX - textWidth / 2f, baseline, labelPaint);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return value * density;
    }
}
