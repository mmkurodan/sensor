package com.example.sensor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SensorInfoOverlay extends View {
    private Paint backgroundPaint;
    private Paint textPaint;
    private Paint titlePaint;
    private Paint buttonPaint;
    private Paint buttonTextPaint;
    private Paint circlePaint;
    private Paint needlePaint;
    private Paint labelPaint;
    private List<String> sensorInfoLines = new ArrayList<>();
    private String compassAzimuth = "--°";
    private float[] gyroValues = new float[3];
    private boolean visible = true;
    private static final float PANEL_HEIGHT_RATIO = 0.33f;
    private float screenHeight = 0;
    private float screenWidth = 0;
    private RectF toggleButtonRect;
    private Runnable onReturnToLocationClicked;

    public SensorInfoOverlay(Context context) {
        super(context);
        init();
    }

    private void init() {
        backgroundPaint = new Paint();
        backgroundPaint.setColor(Color.parseColor("#DD1E1E1E"));
        backgroundPaint.setStyle(Paint.Style.FILL);

        titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.parseColor("#4FC3F7"));
        titlePaint.setTextSize(24);
        titlePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.parseColor("#76FF03"));
        textPaint.setTextSize(18);
        textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        buttonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        buttonPaint.setColor(Color.parseColor("#4FC3F7"));
        buttonPaint.setStyle(Paint.Style.STROKE);
        buttonPaint.setStrokeWidth(2);

        buttonTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        buttonTextPaint.setColor(Color.parseColor("#4FC3F7"));
        buttonTextPaint.setTextSize(14);
        buttonTextPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(Color.parseColor("#2E2E2E"));
        circlePaint.setStyle(Paint.Style.FILL);

        needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        needlePaint.setColor(Color.parseColor("#FF6B6B"));
        needlePaint.setStyle(Paint.Style.FILL);

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(Color.parseColor("#AAAAAA"));
        labelPaint.setTextSize(12);
    }

    public void setCompassAzimuth(float azimuth) {
        this.compassAzimuth = String.format(Locale.getDefault(), "%.0f°", azimuth);
        invalidate();
    }

    public void setGyroValues(float x, float y, float z) {
        gyroValues[0] = x;
        gyroValues[1] = y;
        gyroValues[2] = z;
        invalidate();
    }

    public void setSensorInfo(List<String> lines) {
        this.sensorInfoLines = new ArrayList<>(lines);
        invalidate();
    }

    public void toggleVisibility() {
        visible = !visible;
        invalidate();
    }

    public void setOnReturnToLocationClicked(Runnable callback) {
        this.onReturnToLocationClicked = callback;
    }

    public boolean isVisible() {
        return visible;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (!visible) {
            return;
        }

        int width = canvas.getWidth();
        int height = canvas.getHeight();
        screenWidth = width;
        screenHeight = height;

        float panelHeight = height * PANEL_HEIGHT_RATIO;
        float panelTop = height - panelHeight;

        // Draw background
        RectF panelRect = new RectF(0, panelTop, width, height);
        canvas.drawRect(panelRect, backgroundPaint);

        int padding = 16;
        int lineHeight = 28;
        float yPos = panelTop + padding;

        // Draw title and toggle button
        canvas.drawText("センサー情報", padding, yPos, titlePaint);
        
        // Draw toggle button (X to hide)
        toggleButtonRect = new RectF(width - 60, panelTop + 8, width - 12, panelTop + 40);
        canvas.drawRect(toggleButtonRect, buttonPaint);
        canvas.drawText("Hide", toggleButtonRect.left + 8, toggleButtonRect.centerY() + 5, buttonTextPaint);

        yPos += lineHeight + 8;

        // Draw GPS info and return button in a row
        if (sensorInfoLines.size() > 0) {
            canvas.drawText(sensorInfoLines.get(0), padding, yPos, textPaint);
        }
        
        RectF returnButtonRect = new RectF(width - 160, panelTop + 8, width - 68, panelTop + 40);
        canvas.drawRect(returnButtonRect, buttonPaint);
        canvas.drawText("▶ Location", returnButtonRect.left + 6, returnButtonRect.centerY() + 5, buttonTextPaint);

        yPos += lineHeight;

        // Draw remaining sensor info
        for (int i = 1; i < sensorInfoLines.size(); i++) {
            if (yPos < height - 20) {
                canvas.drawText(sensorInfoLines.get(i), padding, yPos, textPaint);
                yPos += lineHeight;
            }
        }

        // Draw compass and gyro visualizations
        float visualStartY = panelTop + lineHeight * 2 + 40;
        drawCompassVisual(canvas, width / 4, visualStartY);
        drawGyroVisual(canvas, 3 * width / 4, visualStartY);
    }

    private void drawCompassVisual(Canvas canvas, float centerX, float centerY) {
        float radius = 45;

        // Draw circle
        canvas.drawCircle(centerX, centerY, radius, circlePaint);

        // Draw cardinal directions
        Paint directionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        directionPaint.setColor(Color.parseColor("#AAAAAA"));
        directionPaint.setTextSize(14);
        canvas.drawText("N", centerX - 6, centerY - radius - 8, directionPaint);
        canvas.drawText("S", centerX - 6, centerY + radius + 16, directionPaint);
        canvas.drawText("E", centerX + radius - 4, centerY + 6, directionPaint);
        canvas.drawText("W", centerX - radius - 12, centerY + 6, directionPaint);

        // Draw needle based on azimuth
        try {
            float azimuthVal = Float.parseFloat(compassAzimuth.replace("°", ""));
            canvas.save();
            canvas.rotate(-azimuthVal, centerX, centerY);
            
            Path needlePath = new Path();
            needlePath.moveTo(centerX, centerY - radius * 0.7f);
            needlePath.lineTo(centerX - radius * 0.12f, centerY + radius * 0.4f);
            needlePath.lineTo(centerX + radius * 0.12f, centerY + radius * 0.4f);
            needlePath.close();
            canvas.drawPath(needlePath, needlePaint);
            
            canvas.restore();
        } catch (Exception e) {
            // Default needle pointing up
            Path needlePath = new Path();
            needlePath.moveTo(centerX, centerY - radius * 0.7f);
            needlePath.lineTo(centerX - radius * 0.12f, centerY + radius * 0.4f);
            needlePath.lineTo(centerX + radius * 0.12f, centerY + radius * 0.4f);
            needlePath.close();
            canvas.drawPath(needlePath, needlePaint);
        }

        // Draw label
        Paint labelPaint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint2.setColor(Color.parseColor("#AAAAAA"));
        labelPaint2.setTextSize(12);
        canvas.drawText("コンパス: " + compassAzimuth, centerX - 40, centerY + radius + 35, labelPaint2);
    }

    private void drawGyroVisual(Canvas canvas, float centerX, float centerY) {
        float radius = 45;

        // Draw background circle
        canvas.drawCircle(centerX, centerY, radius, circlePaint);

        // Draw crosshair
        Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        crosshairPaint.setColor(Color.parseColor("#666666"));
        crosshairPaint.setStrokeWidth(1);
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, crosshairPaint);
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, crosshairPaint);

        // Draw gyro vector
        float maxValue = 3.0f;
        float x = (gyroValues[0] / maxValue) * radius * 0.8f;
        float y = (gyroValues[1] / maxValue) * radius * 0.8f;
        
        Paint gyroPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gyroPaint.setColor(Color.parseColor("#FFD700"));
        gyroPaint.setStrokeWidth(3);
        canvas.drawLine(centerX, centerY, centerX + x, centerY + y, gyroPaint);
        canvas.drawCircle(centerX + x, centerY + y, 4, gyroPaint);

        // Draw label
        Paint labelPaint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint2.setColor(Color.parseColor("#AAAAAA"));
        labelPaint2.setTextSize(12);
        String gyroText = String.format(Locale.getDefault(),
                "ジャイロ\nX:%.2f Y:%.2f\nZ:%.2f", 
                gyroValues[0], gyroValues[1], gyroValues[2]);
        canvas.drawText(gyroText, centerX - 40, centerY + radius + 35, labelPaint2);
    }

    private void drawArrow(Canvas canvas, float x, float y, boolean pointUp) {
        Path path = new Path();
        float size = 12;

        if (pointUp) {
            path.moveTo(x, y - size / 2);
            path.lineTo(x - size / 2, y + size / 2);
            path.lineTo(x + size / 2, y + size / 2);
        } else {
            path.moveTo(x, y + size / 2);
            path.lineTo(x - size / 2, y - size / 2);
            path.lineTo(x + size / 2, y - size / 2);
        }
        path.close();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // Check if toggle button was pressed
                if (toggleButtonRect != null && toggleButtonRect.contains(event.getX(), event.getY())) {
                    toggleVisibility();
                    return true;
                }
                
                // Check if return to location button was pressed
                if (event.getX() > screenWidth - 160 && event.getX() < screenWidth - 68 &&
                    event.getY() > screenHeight - screenHeight * PANEL_HEIGHT_RATIO + 8 &&
                    event.getY() < screenHeight - screenHeight * PANEL_HEIGHT_RATIO + 40) {
                    if (onReturnToLocationClicked != null) {
                        onReturnToLocationClicked.run();
                    }
                    return true;
                }
                return false;
        }
        return super.onTouchEvent(event);
    }
}
