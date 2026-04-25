package com.example.sensor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;

public class SensorInfoOverlay extends View {
    private Paint backgroundPaint;
    private Paint textPaint;
    private Paint titlePaint;
    private Paint arrowPaint;
    private List<String> sensorInfoLines = new ArrayList<>();
    private String compassAzimuth = "--°";
    private boolean expanded = false;
    private float scrollOffset = 0;
    private float lastY = 0;
    private float dragStartY = 0;
    private static final float PANEL_HEIGHT = 250;
    private static final float HANDLE_HEIGHT = 50;
    private float panelOffset = 0;
    private Handler animationHandler = new Handler(Looper.getMainLooper());
    private static final int ANIMATION_DURATION = 300;
    private int screenHeight = 0;

    public SensorInfoOverlay(Context context) {
        super(context);
        init();
    }

    private void init() {
        backgroundPaint = new Paint();
        backgroundPaint.setColor(Color.parseColor("#DD1E1E1E"));
        backgroundPaint.setStyle(Paint.Style.FILL);

        arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setColor(Color.parseColor("#4FC3F7"));
        arrowPaint.setStyle(Paint.Style.FILL);
        arrowPaint.setStrokeWidth(2);

        titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.parseColor("#4FC3F7"));
        titlePaint.setTextSize(16);
        titlePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.parseColor("#76FF03"));
        textPaint.setTextSize(13);
        textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        panelOffset = PANEL_HEIGHT;
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
        if (expanded) {
            collapse();
        } else {
            expand();
        }
    }

    private void expand() {
        expanded = true;
        animateTo(0);
    }

    private void collapse() {
        expanded = false;
        animateTo(PANEL_HEIGHT);
    }

    private void animateTo(float targetOffset) {
        animationHandler.removeCallbacksAndMessages(null);
        final float startOffset = panelOffset;
        final long startTime = System.currentTimeMillis();
        final DecelerateInterpolator interpolator = new DecelerateInterpolator();

        animationHandler.post(new Runnable() {
            @Override
            public void run() {
                long elapsed = System.currentTimeMillis() - startTime;
                float progress = Math.min(1.0f, (float) elapsed / ANIMATION_DURATION);
                progress = interpolator.getInterpolation(progress);
                panelOffset = startOffset + (targetOffset - startOffset) * progress;
                invalidate();

                if (progress < 1.0f) {
                    animationHandler.post(this);
                }
            }
        });
    }

    public boolean isVisible() {
        return expanded;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = canvas.getWidth();
        int height = canvas.getHeight();
        screenHeight = height;

        float panelTop = height - panelOffset;
        float handleTop = panelTop;
        float contentTop = handleTop + HANDLE_HEIGHT;
        float panelBottom = height;

        // Draw handle area with arrow
        RectF handleRect = new RectF(0, handleTop, width, contentTop);
        canvas.drawRect(handleRect, backgroundPaint);

        // Draw arrow
        drawArrow(canvas, width / 2, handleTop + HANDLE_HEIGHT / 2, expanded);

        // Draw content area (visible only when expanded)
        if (panelOffset < PANEL_HEIGHT) {
            RectF contentRect = new RectF(0, contentTop, width, panelBottom);
            canvas.drawRect(contentRect, backgroundPaint);

            int padding = 12;
            int lineHeight = 20;

            // Draw title
            int textY = (int) (contentTop + padding + 20);
            canvas.drawText("センサー情報", padding, textY, titlePaint);

            // Draw compass azimuth
            textY += lineHeight;
            canvas.drawText("方位: " + compassAzimuth, padding, textY, textPaint);

            // Draw sensor lines
            for (String line : sensorInfoLines) {
                textY += lineHeight;
                if (textY < panelBottom - 10) {
                    canvas.drawText(line, padding, textY, textPaint);
                }
            }
        }
    }

    private void drawArrow(Canvas canvas, float x, float y, boolean pointUp) {
        Path path = new Path();
        float size = 12;

        if (pointUp) {
            // Up arrow
            path.moveTo(x, y - size / 2);
            path.lineTo(x - size / 2, y + size / 2);
            path.lineTo(x + size / 2, y + size / 2);
        } else {
            // Down arrow
            path.moveTo(x, y + size / 2);
            path.lineTo(x - size / 2, y - size / 2);
            path.lineTo(x + size / 2, y - size / 2);
        }
        path.close();
        canvas.drawPath(path, arrowPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (screenHeight == 0) screenHeight = getHeight();
        
        float panelTop = screenHeight - panelOffset;
        float panelBottom = screenHeight;

        // Only handle touch events within the panel area
        if (event.getY() < panelTop) {
            return false; // Let MapView handle touches above the panel
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dragStartY = event.getY();
                lastY = dragStartY;
                animationHandler.removeCallbacksAndMessages(null);
                return true;

            case MotionEvent.ACTION_MOVE:
                float dy = event.getY() - lastY;
                panelOffset -= dy;
                panelOffset = Math.max(0, Math.min(panelOffset, PANEL_HEIGHT));
                lastY = event.getY();
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                float totalDrag = dragStartY - event.getY();
                if (Math.abs(totalDrag) > HANDLE_HEIGHT / 3) {
                    if (totalDrag > 0) {
                        expand();
                    } else {
                        collapse();
                    }
                } else {
                    // Snap to nearest state
                    if (panelOffset < PANEL_HEIGHT / 2) {
                        expand();
                    } else {
                        collapse();
                    }
                }
                return true;
        }
        return super.onTouchEvent(event);
    }
}
