package com.example.sensor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

public class CompassView extends View {
    private Paint circlePaint;
    private Paint needlePaint;
    private float azimuth = 0f;

    public CompassView(Context context) {
        super(context);
        init();
    }

    public CompassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(Color.DKGRAY);
        circlePaint.setStyle(Paint.Style.FILL);

        needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        needlePaint.setColor(Color.RED);
        needlePaint.setStyle(Paint.Style.FILL);
    }

    public void setAzimuth(float azimuth) {
        this.azimuth = azimuth;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(cx, cy) - 8f;

        // Background circle
        canvas.drawCircle(cx, cy, r, circlePaint);

        // Needle (pointing up when azimuth == 0)
        canvas.save();
        canvas.rotate(-azimuth, cx, cy);
        Path path = new Path();
        path.moveTo(cx, cy - r * 0.85f);
        path.lineTo(cx - r * 0.15f, cy + r * 0.5f);
        path.lineTo(cx + r * 0.15f, cy + r * 0.5f);
        path.close();
        canvas.drawPath(path, needlePaint);
        canvas.restore();
    }
}
