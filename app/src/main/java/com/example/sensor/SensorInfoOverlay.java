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
    private static final String DEFAULT_LOCATION_TEXT = "位置情報: 取得待機中";

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
    private final List<String> sensorInfoLines = new ArrayList<>();
    private final float[] gyroValues = new float[3];
    private final RectF panelRect = new RectF();
    private final RectF toggleButtonRect = new RectF();
    private final RectF returnButtonRect = new RectF();

    private final float density;
    private final float scaledDensity;

    private float compassAzimuth = Float.NaN;
    private boolean visible = true;
    private Runnable onReturnToLocationClicked;

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
    }

    public void setCompassAzimuth(float azimuth) {
        compassAzimuth = azimuth;
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
        invalidate();
    }

    public void setOnReturnToLocationClicked(Runnable callback) {
        onReturnToLocationClicked = callback;
    }

    public boolean isVisible() {
        return visible;
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
        String toggleLabel = visible ? "情報を隠す" : "情報を表示";
        String returnLabel = "現在地に戻る";

        float maxButtonTextWidth = Math.max(buttonTextPaint.measureText(toggleLabel), buttonTextPaint.measureText(returnLabel));
        float buttonWidth = Math.max(dp(128), maxButtonTextWidth + buttonHorizontalPadding * 2f);
        float buttonRight = width - outerPadding;
        float buttonLeft = buttonRight - buttonWidth;

        returnButtonRect.set(buttonLeft, outerPadding, buttonRight, outerPadding + buttonHeight);
        toggleButtonRect.set(
                buttonLeft,
                returnButtonRect.bottom + buttonVerticalSpacing,
                buttonRight,
                returnButtonRect.bottom + buttonVerticalSpacing + buttonHeight
        );

        float locationMaxTextSize = Math.min(sp(40), width * 0.12f);
        locationPaint.setTextSize(fitTextSize(locationLine, width - panelPadding * 2f, sp(12), locationMaxTextSize));

        if (visible) {
            float titleHeight = titlePaint.getFontSpacing();
            float locationHeight = locationPaint.getFontSpacing();
            float detailHeight = detailPaint.getFontSpacing();
            float labelHeight = labelPaint.getFontSpacing();
            float visualSize = width * VISUAL_SIZE_RATIO;
            float visualLabelHeight = labelHeight * 3f + dp(8);
            float detailBlockHeight = sensorInfoLines.size() > 1
                    ? sectionSpacing + (sensorInfoLines.size() - 1) * detailHeight
                    : 0f;
            float panelHeight = panelPadding
                    + titleHeight
                    + sectionSpacing
                    + locationHeight
                    + sectionSpacing
                    + visualSize
                    + visualLabelHeight
                    + detailBlockHeight
                    + panelPadding;

            panelRect.set(0, height - panelHeight, width, height);
            canvas.drawRoundRect(panelRect, dp(20), dp(20), backgroundPaint);

            float contentTop = panelRect.top + panelPadding;
            float titleBaseline = contentTop - titlePaint.ascent();
            canvas.drawText("センサー情報", panelPadding, titleBaseline, titlePaint);

            float locationTop = contentTop + titleHeight + sectionSpacing;
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

            if (sensorInfoLines.size() > 1) {
                float detailTop = labelTop + labelHeight * 3f + sectionSpacing;
                for (int i = 1; i < sensorInfoLines.size(); i++) {
                    float detailBaseline = detailTop - detailPaint.ascent();
                    canvas.drawText(sensorInfoLines.get(i), panelPadding, detailBaseline, detailPaint);
                    detailTop += detailHeight;
                }
            }
        }

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
                return returnButtonRect.contains(x, y) || toggleButtonRect.contains(x, y);
            case MotionEvent.ACTION_UP:
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
