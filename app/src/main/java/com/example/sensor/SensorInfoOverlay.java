package com.example.sensor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SensorInfoOverlay extends View {
    private static final float VISUAL_SIZE_RATIO = 0.30f;
    private static final String DEFAULT_LOCATION_TEXT = "緯度: --, 経度: --";

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint locationPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint detailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buttonFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buttonStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buttonTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint directionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint southMarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint altitudeBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint altitudeStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint altitudeFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint altitudeLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<String> sensorInfoLines = new ArrayList<>();
    private final float[] gyroValues = new float[3];
    private final RectF panelRect = new RectF();
    private final RectF roadNetworkButtonRect = new RectF();
    private final RectF trackingButtonRect = new RectF();
    private final RectF toggleButtonRect = new RectF();
    private final RectF returnButtonRect = new RectF();

    private final float density;
    private final float scaledDensity;

    private float compassAzimuth = Float.NaN;
    private double altitude = Double.NaN;
    private boolean visible = true;
    private boolean roadNetworkVisible;
    private boolean trackingCenteringEnabled = true;
    private Runnable onRoadNetworkToggleClicked;
    private Runnable onReturnToLocationClicked;
    private Runnable onTrackingCenteringToggleClicked;
    private Runnable onPanelVisibilityChanged;

    public SensorInfoOverlay(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        init();
    }

    private void init() {
        backgroundPaint.setColor(Color.parseColor("#DD1E1E1E"));
        backgroundPaint.setStyle(Paint.Style.FILL);

        titlePaint.setColor(Color.parseColor("#4FC3F7"));
        titlePaint.setTextSize(sp(18));
        titlePaint.setTypeface(Typeface.DEFAULT_BOLD);

        locationPaint.setColor(Color.parseColor("#76FF03"));
        locationPaint.setTextSize(sp(20));
        locationPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        detailPaint.setColor(Color.parseColor("#76FF03"));
        detailPaint.setTextSize(sp(12));
        detailPaint.setTypeface(Typeface.MONOSPACE);

        buttonFillPaint.setColor(Color.parseColor("#CC1E1E1E"));
        buttonFillPaint.setStyle(Paint.Style.FILL);

        buttonStrokePaint.setColor(Color.parseColor("#4FC3F7"));
        buttonStrokePaint.setStyle(Paint.Style.STROKE);
        buttonStrokePaint.setStrokeWidth(dp(2));

        buttonTextPaint.setColor(Color.parseColor("#4FC3F7"));
        buttonTextPaint.setTextSize(sp(13));
        buttonTextPaint.setTypeface(Typeface.DEFAULT_BOLD);

        circlePaint.setColor(Color.parseColor("#2E2E2E"));
        circlePaint.setStyle(Paint.Style.FILL);

        circleStrokePaint.setColor(Color.parseColor("#4FC3F7"));
        circleStrokePaint.setStyle(Paint.Style.STROKE);
        circleStrokePaint.setStrokeWidth(dp(2));

        needlePaint.setColor(Color.parseColor("#FF6B6B"));
        needlePaint.setStyle(Paint.Style.FILL);

        axisPaint.setColor(Color.parseColor("#666666"));
        axisPaint.setStrokeWidth(dp(1));

        labelPaint.setColor(Color.parseColor("#AAAAAA"));
        labelPaint.setTextSize(sp(12));

        directionPaint.setColor(Color.parseColor("#AAAAAA"));
        directionPaint.setTextSize(sp(12));
        directionPaint.setTypeface(Typeface.DEFAULT_BOLD);

        southMarkerPaint.setColor(Color.WHITE);
        southMarkerPaint.setStyle(Paint.Style.FILL);

        altitudeBackgroundPaint.setColor(Color.parseColor("#2E2E2E"));
        altitudeBackgroundPaint.setStyle(Paint.Style.FILL);

        altitudeStrokePaint.setColor(Color.parseColor("#4FC3F7"));
        altitudeStrokePaint.setStyle(Paint.Style.STROKE);
        altitudeStrokePaint.setStrokeWidth(dp(2));

        altitudeFillPaint.setColor(Color.parseColor("#FF9800"));
        altitudeFillPaint.setStyle(Paint.Style.FILL);

        altitudeLabelPaint.setColor(Color.parseColor("#4FC3F7"));
        altitudeLabelPaint.setTextSize(sp(11));
        altitudeLabelPaint.setTypeface(Typeface.MONOSPACE);
    }

    public void setCompassAzimuth(float azimuth) {
        compassAzimuth = azimuth;
        invalidate();
    }

    public void setAltitude(double alt) {
        altitude = alt;
        invalidate();
    }

    public void setGyroValues(float x, float y, float z) {
        gyroValues[0] = x;
        gyroValues[1] = y;
        gyroValues[2] = z;
        invalidate();
    }

    public void setSensorInfo(List<String> lines) {
        sensorInfoLines.clear();
        sensorInfoLines.addAll(lines);
        invalidate();
    }

    public void toggleVisibility() {
        visible = !visible;
        if (onPanelVisibilityChanged != null) {
            onPanelVisibilityChanged.run();
        }
        invalidate();
    }

    public void setOnReturnToLocationClicked(Runnable callback) {
        onReturnToLocationClicked = callback;
    }

    public void setOnRoadNetworkToggleClicked(Runnable callback) {
        onRoadNetworkToggleClicked = callback;
    }

    public void setOnTrackingCenteringToggleClicked(Runnable callback) {
        onTrackingCenteringToggleClicked = callback;
    }

    public void setOnPanelVisibilityChanged(Runnable callback) {
        onPanelVisibilityChanged = callback;
    }

    public void setTrackingCenteringEnabled(boolean enabled) {
        trackingCenteringEnabled = enabled;
        invalidate();
    }

    public void setRoadNetworkVisible(boolean visible) {
        roadNetworkVisible = visible;
        invalidate();
    }

    public boolean isVisible() {
        return visible;
    }

    public float getOccupiedBottomInset() {
        if (!visible || getWidth() <= 0) {
            return 0f;
        }
        return calculatePanelHeight(getWidth(), sensorInfoLines.isEmpty() ? DEFAULT_LOCATION_TEXT : sensorInfoLines.get(0));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = canvas.getWidth();
        float height = canvas.getHeight();
        float outerPadding = dp(16);
        float panelPadding = dp(18);
        float sectionSpacing = dp(14);
        float buttonHeight = dp(42);
        float buttonHorizontalPadding = dp(14);
        float buttonVerticalSpacing = dp(10);
        float buttonCornerRadius = dp(12);

        String locationLine = sensorInfoLines.isEmpty() ? DEFAULT_LOCATION_TEXT : sensorInfoLines.get(0);
        String roadNetworkLabel = roadNetworkVisible ? "道路網 ON" : "道路網 OFF";
        String trackingLabel = trackingCenteringEnabled ? "追跡/中心 ON" : "追跡/中心 OFF";
        String toggleLabel = visible ? "情報を隠す" : "情報を表示";
        String returnLabel = "現在地に戻る";

        float maxButtonTextWidth = Math.max(
                Math.max(buttonTextPaint.measureText(roadNetworkLabel), buttonTextPaint.measureText(trackingLabel)),
                Math.max(buttonTextPaint.measureText(toggleLabel), buttonTextPaint.measureText(returnLabel))
        );
        float buttonWidth = Math.max(dp(128), maxButtonTextWidth + buttonHorizontalPadding * 2f);
        float buttonRight = width - outerPadding;
        float buttonLeft = buttonRight - buttonWidth;

        // Place buttons at the bottom with safe area consideration
        float bottomMargin = dp(16); // Safe area margin
        float toggleButtonBottom = height - bottomMargin;
        float returnButtonBottom = toggleButtonBottom - buttonHeight - buttonVerticalSpacing;
        float trackingButtonBottom = returnButtonBottom - buttonHeight - buttonVerticalSpacing;
        float roadNetworkButtonBottom = trackingButtonBottom - buttonHeight - buttonVerticalSpacing;

        roadNetworkButtonRect.set(
                buttonLeft,
                roadNetworkButtonBottom - buttonHeight,
                buttonRight,
                roadNetworkButtonBottom
        );

        trackingButtonRect.set(
                buttonLeft,
                trackingButtonBottom - buttonHeight,
                buttonRight,
                trackingButtonBottom
        );
        toggleButtonRect.set(
                buttonLeft,
                toggleButtonBottom - buttonHeight,
                buttonRight,
                toggleButtonBottom
        );
        returnButtonRect.set(
                buttonLeft,
                returnButtonBottom - buttonHeight,
                buttonRight,
                returnButtonBottom
        );

        float locationMaxTextSize = Math.min(sp(40), width * 0.12f);
        locationPaint.setTextSize(fitTextSize(locationLine, width - panelPadding * 2f, sp(12), locationMaxTextSize));

        if (visible) {
            float locationHeight = locationPaint.getFontSpacing();
            float detailHeight = detailPaint.getFontSpacing();
            float labelHeight = labelPaint.getFontSpacing();
            float visualSize = width * VISUAL_SIZE_RATIO;
            float altitudeGaugeHeight = visualSize * 0.6f;
            float panelHeight = calculatePanelHeight(width, locationLine);

            panelRect.set(0, height - panelHeight, width, height);
            canvas.drawRoundRect(panelRect, dp(20), dp(20), backgroundPaint);

            float contentTop = panelRect.top + panelPadding;
            float locationTop = contentTop;
            float locationBaseline = locationTop - locationPaint.ascent();
            canvas.drawText(locationLine, panelPadding, locationBaseline, locationPaint);

            float visualTop = locationTop + locationHeight + sectionSpacing;
            float visualCenterY = visualTop + visualSize / 2f;
            float compassCenterX = width * 0.30f;
            float gyroCenterX = width * 0.70f;

            drawCompassVisual(canvas, compassCenterX, visualCenterY, visualSize);
            drawGyroVisual(canvas, gyroCenterX, visualCenterY, visualSize);

            float labelTop = visualTop + visualSize + dp(8);
            drawCenteredLines(canvas, new String[]{"コンパス", getCompassAzimuthText()}, compassCenterX, labelTop, labelPaint);
            drawCenteredLines(
                    canvas,
                    new String[]{
                            "ジャイロ",
                            String.format(Locale.getDefault(), "X: %.2f  Y: %.2f", gyroValues[0], gyroValues[1]),
                            String.format(Locale.getDefault(), "Z: %.2f rad/s", gyroValues[2])
                    },
                    gyroCenterX,
                    labelTop,
                    labelPaint
            );

            float altitudeTop = labelTop + labelHeight * 3f + sectionSpacing;
            drawAltitudeGauge(canvas, panelPadding, altitudeTop, width - panelPadding * 2f, altitudeGaugeHeight);

            if (sensorInfoLines.size() > 1) {
                float detailTop = altitudeTop + altitudeGaugeHeight + sectionSpacing;
                for (int i = 1; i < sensorInfoLines.size(); i++) {
                    float detailBaseline = detailTop - detailPaint.ascent();
                    canvas.drawText(sensorInfoLines.get(i), panelPadding, detailBaseline, detailPaint);
                    detailTop += detailHeight;
                }
            }
        }

        drawButton(canvas, roadNetworkButtonRect, roadNetworkLabel, buttonCornerRadius);
        drawButton(canvas, trackingButtonRect, trackingLabel, buttonCornerRadius);
        drawButton(canvas, returnButtonRect, returnLabel, buttonCornerRadius);
        drawButton(canvas, toggleButtonRect, toggleLabel, buttonCornerRadius);
    }

    private void drawCompassVisual(Canvas canvas, float centerX, float centerY, float size) {
        float radius = size / 2f;

        canvas.drawCircle(centerX, centerY, radius, circlePaint);
        canvas.drawCircle(centerX, centerY, radius, circleStrokePaint);

        float labelOffset = dp(12);
        drawCenteredText(canvas, "N", centerX, centerY - radius - labelOffset, directionPaint);
        drawCenteredText(canvas, "S", centerX, centerY + radius + labelOffset, directionPaint);
        drawCenteredText(canvas, "E", centerX + radius + labelOffset, centerY, directionPaint);
        drawCenteredText(canvas, "W", centerX - radius - labelOffset, centerY, directionPaint);

        canvas.save();
        if (!Float.isNaN(compassAzimuth)) {
            canvas.rotate(-compassAzimuth, centerX, centerY);
        }

        Path needlePath = new Path();
        needlePath.moveTo(centerX, centerY - radius * 0.78f);
        needlePath.lineTo(centerX - radius * 0.16f, centerY + radius * 0.30f);
        needlePath.lineTo(centerX + radius * 0.16f, centerY + radius * 0.30f);
        needlePath.close();
        canvas.drawPath(needlePath, needlePaint);
        canvas.restore();
    }

    private void drawGyroVisual(Canvas canvas, float centerX, float centerY, float size) {
        float radius = size / 2f;
        float maxValue = 3.0f;
        float vectorLimit = radius * 0.75f;
        float x = clamp(gyroValues[0] / maxValue, -1f, 1f) * vectorLimit;
        float y = clamp(gyroValues[1] / maxValue, -1f, 1f) * vectorLimit;

        canvas.drawCircle(centerX, centerY, radius, circlePaint);
        canvas.drawCircle(centerX, centerY, radius, circleStrokePaint);
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, axisPaint);
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, axisPaint);
        canvas.drawLine(centerX, centerY, centerX + x, centerY + y, needlePaint);
        canvas.drawCircle(centerX + x, centerY + y, dp(4), needlePaint);
    }

    private void drawAltitudeGauge(Canvas canvas, float left, float top, float width, float height) {
        float maxAltitude = 3000.0f;
        float barHeight = height * 0.6f;
        float labelHeight = height * 0.4f;
        
        float barLeft = left + dp(10);
        float barRight = left + width - dp(10);
        float barTop = top + dp(4);
        float barBottom = barTop + barHeight;
        
        RectF barBackground = new RectF(barLeft, barTop, barRight, barBottom);
        canvas.drawRoundRect(barBackground, dp(4), dp(4), altitudeBackgroundPaint);
        canvas.drawRoundRect(barBackground, dp(4), dp(4), altitudeStrokePaint);
        
        if (!Double.isNaN(altitude) && altitude >= 0) {
            float fillRatio = (float) Math.min(altitude / maxAltitude, 1.0);
            float fillWidth = (barRight - barLeft) * fillRatio;
            RectF barFill = new RectF(barLeft, barTop, barLeft + fillWidth, barBottom);
            canvas.drawRoundRect(barFill, dp(4), dp(4), altitudeFillPaint);
            
            String altText = String.format(Locale.getDefault(), "高度: %.1f m", altitude);
            float textX = barLeft + dp(8);
            float textY = barBottom + labelHeight * 0.8f;
            canvas.drawText(altText, textX, textY, altitudeLabelPaint);
        } else {
            String noAltText = "高度: 取得待機中";
            float textX = barLeft + dp(8);
            float textY = barBottom + labelHeight * 0.8f;
            canvas.drawText(noAltText, textX, textY, altitudeLabelPaint);
        }
    }

    private void drawButton(Canvas canvas, RectF rect, String label, float cornerRadius) {
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, buttonFillPaint);
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, buttonStrokePaint);

        float textX = rect.centerX() - buttonTextPaint.measureText(label) / 2f;
        float textY = rect.centerY() - (buttonTextPaint.ascent() + buttonTextPaint.descent()) / 2f;
        canvas.drawText(label, textX, textY, buttonTextPaint);
    }

    private void drawCenteredLines(Canvas canvas, String[] lines, float centerX, float top, Paint paint) {
        float baseline = top - paint.ascent();
        for (String line : lines) {
            canvas.drawText(line, centerX - paint.measureText(line) / 2f, baseline, paint);
            baseline += paint.getFontSpacing();
        }
    }

    private void drawCenteredText(Canvas canvas, String text, float centerX, float centerY, Paint paint) {
        float x = centerX - paint.measureText(text) / 2f;
        float y = centerY - (paint.ascent() + paint.descent()) / 2f;
        canvas.drawText(text, x, y, paint);
    }

    private String getCompassAzimuthText() {
        return Float.isNaN(compassAzimuth)
                ? "--°"
                : String.format(Locale.getDefault(), "%.0f°", compassAzimuth);
    }

    private float fitTextSize(String text, float availableWidth, float minSize, float maxSize) {
        if (text == null || text.isEmpty()) {
            return maxSize;
        }

        float low = minSize;
        float high = maxSize;
        for (int i = 0; i < 16; i++) {
            float mid = (low + high) / 2f;
            locationPaint.setTextSize(mid);
            if (locationPaint.measureText(text) <= availableWidth) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float calculatePanelHeight(float width, String locationLine) {
        float panelPadding = dp(18);
        float sectionSpacing = dp(14);
        float locationMaxTextSize = Math.min(sp(40), width * 0.12f);
        locationPaint.setTextSize(fitTextSize(locationLine, width - panelPadding * 2f, sp(12), locationMaxTextSize));

        float locationHeight = locationPaint.getFontSpacing();
        float detailHeight = detailPaint.getFontSpacing();
        float labelHeight = labelPaint.getFontSpacing();
        float visualSize = width * VISUAL_SIZE_RATIO;
        float visualLabelHeight = labelHeight * 3f + dp(8);
        float altitudeGaugeHeight = visualSize * 0.6f;
        float detailBlockHeight = sensorInfoLines.size() > 1
                ? sectionSpacing + (sensorInfoLines.size() - 1) * detailHeight
                : 0f;

        return panelPadding
                + locationHeight
                + sectionSpacing
                + visualSize
                + visualLabelHeight
                + altitudeGaugeHeight
                + sectionSpacing
                + detailBlockHeight
                + panelPadding;
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return value * scaledDensity;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                return roadNetworkButtonRect.contains(x, y)
                        || trackingButtonRect.contains(x, y)
                        || returnButtonRect.contains(x, y)
                        || toggleButtonRect.contains(x, y);
            case MotionEvent.ACTION_UP:
                if (roadNetworkButtonRect.contains(x, y)) {
                    performClick();
                    if (onRoadNetworkToggleClicked != null) {
                        onRoadNetworkToggleClicked.run();
                    }
                    return true;
                }
                if (trackingButtonRect.contains(x, y)) {
                    performClick();
                    if (onTrackingCenteringToggleClicked != null) {
                        onTrackingCenteringToggleClicked.run();
                    }
                    return true;
                }
                if (returnButtonRect.contains(x, y)) {
                    performClick();
                    if (onReturnToLocationClicked != null) {
                        onReturnToLocationClicked.run();
                    }
                    return true;
                }
                if (toggleButtonRect.contains(x, y)) {
                    performClick();
                    toggleVisibility();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
