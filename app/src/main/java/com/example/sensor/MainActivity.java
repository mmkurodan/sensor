package com.example.sensor;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {

    private SensorManager sensorManager;
    private Map<Integer, TextView> sensorValueViews = new HashMap<>();
    private Sensor pressureSensor;
    private TextView altitudeView;
    private boolean initialPressureSet = false;
    private double initialAltitude = 0.0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#121212"));

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(32, 32, 32, 32);

        // Title
        TextView title = new TextView(this);
        title.setText("センサーモニター");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 32);
        mainLayout.addView(title);

        // Sensor count
        TextView countView = new TextView(this);
        countView.setText("検出センサー数: " + sensorList.size());
        countView.setTextSize(16);
        countView.setTextColor(Color.parseColor("#AAAAAA"));
        countView.setPadding(0, 0, 0, 32);
        mainLayout.addView(countView);

        // Create view for pressure sensor only
        if (pressureSensor != null) {
            LinearLayout sensorCard = createSensorCard(pressureSensor);
            mainLayout.addView(sensorCard);
            altitudeView = sensorValueViews.get(Sensor.TYPE_PRESSURE);
        } else {
            TextView noPressure = new TextView(this);
            noPressure.setText("気圧センサーが見つかりません");
            noPressure.setTextColor(Color.RED);
            noPressure.setPadding(0, 8, 0, 8);
            mainLayout.addView(noPressure);
        }

        scrollView.addView(mainLayout);
        setContentView(scrollView);
    }

    private LinearLayout createSensorCard(Sensor sensor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#1E1E1E"));
        card.setPadding(24, 24, 24, 24);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 16);
        card.setLayoutParams(cardParams);

        // Sensor name
        TextView nameView = new TextView(this);
        nameView.setText(sensor.getName());
        nameView.setTextSize(16);
        nameView.setTextColor(Color.parseColor("#4FC3F7"));
        nameView.setTypeface(null, Typeface.BOLD);
        card.addView(nameView);

        // Sensor type
        TextView typeView = new TextView(this);
        typeView.setText("タイプ: " + getSensorTypeName(sensor.getType()));
        typeView.setTextSize(12);
        typeView.setTextColor(Color.parseColor("#888888"));
        typeView.setPadding(0, 4, 0, 8);
        card.addView(typeView);

        // Vendor info
        TextView vendorView = new TextView(this);
        vendorView.setText("ベンダー: " + sensor.getVendor() + " | 範囲: " + sensor.getMaximumRange());
        vendorView.setTextSize(11);
        vendorView.setTextColor(Color.parseColor("#666666"));
        vendorView.setPadding(0, 0, 0, 8);
        card.addView(vendorView);

        // Sensor values
        TextView valueView = new TextView(this);
        valueView.setText("データ: 取得中...");
        valueView.setTextSize(14);
        valueView.setTextColor(Color.parseColor("#76FF03"));
        valueView.setTypeface(Typeface.MONOSPACE);
        card.addView(valueView);

        sensorValueViews.put(sensor.getType(), valueView);

        return card;
    }

    private String getSensorTypeName(int type) {
        switch (type) {
            case Sensor.TYPE_ACCELEROMETER: return "加速度計";
            case Sensor.TYPE_MAGNETIC_FIELD: return "磁気センサー";
            case Sensor.TYPE_GYROSCOPE: return "ジャイロスコープ";
            case Sensor.TYPE_LIGHT: return "照度センサー";
            case Sensor.TYPE_PRESSURE: return "気圧センサー";
            case Sensor.TYPE_PROXIMITY: return "近接センサー";
            case Sensor.TYPE_GRAVITY: return "重力センサー";
            case Sensor.TYPE_LINEAR_ACCELERATION: return "線形加速度";
            case Sensor.TYPE_ROTATION_VECTOR: return "回転ベクトル";
            case Sensor.TYPE_RELATIVE_HUMIDITY: return "湿度センサー";
            case Sensor.TYPE_AMBIENT_TEMPERATURE: return "温度センサー";
            case Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED: return "磁気(未較正)";
            case Sensor.TYPE_GAME_ROTATION_VECTOR: return "ゲーム回転ベクトル";
            case Sensor.TYPE_GYROSCOPE_UNCALIBRATED: return "ジャイロ(未較正)";
            case Sensor.TYPE_SIGNIFICANT_MOTION: return "有意な動き";
            case Sensor.TYPE_STEP_DETECTOR: return "歩行検出";
            case Sensor.TYPE_STEP_COUNTER: return "歩数計";
            case Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR: return "地磁気回転ベクトル";
            case Sensor.TYPE_HEART_RATE: return "心拍数";
            case Sensor.TYPE_POSE_6DOF: return "6DoF姿勢";
            case Sensor.TYPE_STATIONARY_DETECT: return "静止検出";
            case Sensor.TYPE_MOTION_DETECT: return "動作検出";
            case Sensor.TYPE_HEART_BEAT: return "心拍";
            case Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT: return "装着検出";
            case Sensor.TYPE_ACCELEROMETER_UNCALIBRATED: return "加速度(未較正)";
            case Sensor.TYPE_HINGE_ANGLE: return "ヒンジ角度";
            default: return "タイプ " + type;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pressureSensor != null) {
            sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
            TextView valueView = sensorValueViews.get(Sensor.TYPE_PRESSURE);
            if (valueView != null && event.values != null && event.values.length > 0) {
                float pressure = event.values[0]; // hPa
                double currentAltitude = pressureToAltitude(pressure);
                if (!initialPressureSet) {
                    initialAltitude = currentAltitude;
                    initialPressureSet = true;
                }
                double delta = currentAltitude - initialAltitude;
                String text = String.format(Locale.getDefault(),
                        "気圧: %.2f hPa\n高度: %.2f m\n起動時との差: %.2f m",
                        pressure, currentAltitude, delta);
                valueView.setText(text);
            }
        }
    }

    private double pressureToAltitude(double pressureHPa) {
        // Convert hPa to meters using the barometric formula
        return 44330.0 * (1.0 - Math.pow(pressureHPa / 1013.25, 1.0/5.255));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used
    }
}
