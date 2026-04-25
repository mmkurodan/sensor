package com.example.sensor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public class SensorInfoOverlay extends View {
    private Paint backgroundPaint;
    private Paint textPaint;
    private Paint titlePaint;
    private List<String> sensorInfoLines = new ArrayList<>();
    private String compassAzimuth = "--°";
    private boolean visible = true;
    private float scrollOffset = 0;
    private float lastY = 0;

    public SensorInfoOverlay(Context context) {
        super(context);
        init();
    }

    private void init() {
        // Semi-transparent background
        backgroundPaint = new Paint();
        backgroundPaint.setColor(Color.parseColor("#AA1E1E1E"));
        backgroundPaint.setStyle(Paint.Style.FILL);

        // Title text
        titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.parseColor("#4FC3F7"));
        titlePaint.setTextSize(18);
        titlePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        // Regular text
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.parseColor("#76FF03"));
        textPaint.setTextSize(14);
        textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
    }

    public void setCompassAzimuth(float azimuth) {
        this.compassAzimuth = String.format("%.0f°", azimuth);
        invalidate();
    }

    public void setSensorInfo(List<String> lines) {
        this.sensorInfoLines = new ArrayList<>(lines);
        scrollOffset = 0;
        invalidate();
    }

    public void toggleVisibility() {
        this.visible = !this.visible;
        invalidate();
    }

    public boolean isVisible() {
        return this.visible;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (!visible) {
            return;
        }

        int width = getWidth();
        int height = getHeight();
        int padding = 16;
        int maxWidth = Math.min(width - 2 * padding, 400);
        int x = (width - maxWidth) / 2;
        int y = padding;

        // Calculate content height
        int lineHeight = 24;
        int titleHeight = 32;
        int contentHeight = titleHeight + (sensorInfoLines.size() + 1) * lineHeight + 2 * padding;

        // Clamp scroll offset
        int maxScroll = Math.max(0, contentHeight - (height - 2 * padding));
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        // Draw semi-transparent background
        RectF bgRect = new RectF(x - padding, y - padding, x + maxWidth + padding, 
                                  Math.min(y + contentHeight + padding, height - padding));
        canvas.drawRoundRect(bgRect, 8, 8, backgroundPaint);

        // Draw title
        int textY = y + titleHeight - (int)scrollOffset;
        canvas.drawText("センサー情報", x, textY, titlePaint);

        // Draw compass azimuth
        textY += lineHeight;
        canvas.drawText("方位: " + compassAzimuth, x + 8, textY, textPaint);

        // Draw sensor lines
        for (String line : sensorInfoLines) {
            textY += lineHeight;
            if (textY > y - padding && textY < height) {
                canvas.drawText(line, x + 8, textY, textPaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dy = event.getY() - lastY;
                scrollOffset -= dy;
                lastY = event.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                lastY = 0;
                return true;
        }
        return super.onTouchEvent(event);
    }
}
