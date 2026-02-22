package com.example.sensor;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements SensorEventListener {

    private static final int REQUEST_LOCATION_PERMISSION = 1001;
    private static final String GPS_LOG_TAG = "GPS_TRACE";
    private static final String GPS_LOG_FILE_NAME = "gps_trace.log";

    private SensorManager sensorManager;
    private final Map<Integer, TextView> sensorValueViews = new HashMap<>();
    private Sensor pressureSensor;
    private Sensor rotationSensor;
    private Sensor gyroSensor;
    private LocationManager locationManager;
    private TextView altitudeView;
    private TextView azimuthView;
    private TextView gyroView;
    private TextView gpsView;
    private CompassView compassView;
    private File gpsLogFile;
    private boolean initialPressureSet = false;
    private boolean gpsFixAcquired = false;
    private double initialAltitude = 0.0;
    private final SimpleDateFormat gpsLogTimeFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private final LocationListener gpsLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            logLocationDetails("onLocationChanged", location);
            if (!gpsFixAcquired && LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
                gpsFixAcquired = true;
                logGpsTrace("GPS fix acquired for the first time.");
            }
            updateGpsDisplay(location, false);
        }

        @Override
        public void onProviderEnabled(String provider) {
            logGpsTrace("onProviderEnabled: provider=" + provider);
            updateGpsStatusText("GPS状態: プロバイダ有効化 (" + provider + ")\n取得待機中...\nログ: " + getGpsLogPath());
        }

        @Override
        public void onProviderDisabled(String provider) {
            logGpsTrace("onProviderDisabled: provider=" + provider);
            updateGpsStatusText("GPS状態: プロバイダ無効 (" + provider + ")\nログ: " + getGpsLogPath());
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            logGpsTrace("onStatusChanged: provider=" + provider + ", status=" + status + ", extras=" + extras);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (sensorManager != null) {
            pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }
        initGpsLogging();
        logGpsTrace("onCreate: activity created. locationManager=" + (locationManager != null));

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
        int detected = 0;
        if (sensorManager != null) {
            detected = sensorManager.getSensorList(Sensor.TYPE_ALL).size();
        }
        countView.setText("検出センサー数: " + detected);
        countView.setTextSize(16);
        countView.setTextColor(Color.parseColor("#AAAAAA"));
        countView.setPadding(0, 0, 0, 32);
        mainLayout.addView(countView);

        // Compass header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, 24);

        compassView = new CompassView(this);
        LinearLayout.LayoutParams compParams = new LinearLayout.LayoutParams(300, 300);
        compParams.setMargins(0, 0, 24, 0);
        compassView.setLayoutParams(compParams);
        header.addView(compassView);

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);

        azimuthView = new TextView(this);
        azimuthView.setText("方位: --°");
        azimuthView.setTextSize(16);
        azimuthView.setTextColor(Color.WHITE);
        infoCol.addView(azimuthView);

        gyroView = new TextView(this);
        gyroView.setText("ジャイロ: x=-- y=-- z=--");
        gyroView.setTextSize(14);
        gyroView.setTextColor(Color.WHITE);
        gyroView.setPadding(0, 8, 0, 0);
        infoCol.addView(gyroView);

        header.addView(infoCol);
        mainLayout.addView(header);
        mainLayout.addView(createGpsCard());

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
        updateGpsStatusText("GPS状態: 初期化完了\n権限確認待ち...\nログ: " + getGpsLogPath());
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

    private LinearLayout createGpsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#1E1E1E"));
        card.setPadding(24, 24, 24, 24);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 16);
        card.setLayoutParams(cardParams);

        TextView titleView = new TextView(this);
        titleView.setText("GPS");
        titleView.setTextSize(16);
        titleView.setTextColor(Color.parseColor("#4FC3F7"));
        titleView.setTypeface(null, Typeface.BOLD);
        card.addView(titleView);

        TextView statusLabel = new TextView(this);
        statusLabel.setText("位置情報 (緯度/経度/高度/精度)");
        statusLabel.setTextSize(12);
        statusLabel.setTextColor(Color.parseColor("#888888"));
        statusLabel.setPadding(0, 4, 0, 8);
        card.addView(statusLabel);

        gpsView = new TextView(this);
        gpsView.setText("GPS状態: 初期化中...\nログ: " + getGpsLogPath());
        gpsView.setTextSize(14);
        gpsView.setTextColor(Color.parseColor("#76FF03"));
        gpsView.setTypeface(Typeface.MONOSPACE);
        card.addView(gpsView);

        return card;
    }

    private void initGpsLogging() {
        // GPS logging disabled by user request; do not create external log files.
        gpsLogFile = null;
    }

    private String getGpsLogPath() {
        return "ログ取得無効";
    }

    private void updateGpsStatusText(String text) {
        if (gpsView != null) {
            gpsView.setText(text);
        }
    }

    private void logGpsTrace(String message) {
        // GPS logging disabled by user request; no-op.
    }

    private void logLocationDetails(String prefix, Location location) {
        if (location == null) {
            logGpsTrace(prefix + ": location=null");
            return;
        }
        String base = String.format(Locale.US,
                "%s: provider=%s, lat=%.8f, lon=%.8f, time=%d, elapsedRealtimeNanos=%d, "
                        + "hasAccuracy=%s, accuracy=%.3f, hasAltitude=%s, altitude=%.3f, "
                        + "hasSpeed=%s, speed=%.3f, hasBearing=%s, bearing=%.3f",
                prefix,
                location.getProvider(),
                location.getLatitude(),
                location.getLongitude(),
                location.getTime(),
                location.getElapsedRealtimeNanos(),
                location.hasAccuracy(),
                location.hasAccuracy() ? location.getAccuracy() : -1f,
                location.hasAltitude(),
                location.hasAltitude() ? location.getAltitude() : -1d,
                location.hasSpeed(),
                location.hasSpeed() ? location.getSpeed() : -1f,
                location.hasBearing(),
                location.hasBearing() ? location.getBearing() : -1f);
        logGpsTrace(base);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            logGpsTrace(String.format(Locale.US,
                    "%s: hasVerticalAccuracy=%s, verticalAccuracy=%.3f, "
                            + "hasSpeedAccuracy=%s, speedAccuracy=%.3f, "
                            + "hasBearingAccuracy=%s, bearingAccuracy=%.3f",
                    prefix,
                    location.hasVerticalAccuracy(),
                    location.hasVerticalAccuracy() ? location.getVerticalAccuracyMeters() : -1f,
                    location.hasSpeedAccuracy(),
                    location.hasSpeedAccuracy() ? location.getSpeedAccuracyMetersPerSecond() : -1f,
                    location.hasBearingAccuracy(),
                    location.hasBearingAccuracy() ? location.getBearingAccuracyDegrees() : -1f));
        }
        if (location.getExtras() != null) {
            logGpsTrace(prefix + ": extras=" + location.getExtras());
        }
    }

    private void updateGpsDisplay(Location location, boolean fromCache) {
        if (location == null) {
            return;
        }
        String altitudeText = location.hasAltitude()
                ? String.format(Locale.getDefault(), "%.2f m", location.getAltitude())
                : "--";
        String accuracyText = location.hasAccuracy()
                ? String.format(Locale.getDefault(), "%.2f m", location.getAccuracy())
                : "--";
        String speedText = location.hasSpeed()
                ? String.format(Locale.getDefault(), "%.2f m/s", location.getSpeed())
                : "--";
        String bearingText = location.hasBearing()
                ? String.format(Locale.getDefault(), "%.2f°", location.getBearing())
                : "--";
        String source = fromCache ? "キャッシュ" : "リアルタイム";
        String text = String.format(Locale.getDefault(),
                "GPS状態: %s\nプロバイダ: %s\n緯度: %.6f\n経度: %.6f\n高度: %s\n精度: %s\n速度: %s\n方位: %s\n時刻: %s\nログ: %s",
                source,
                location.getProvider(),
                location.getLatitude(),
                location.getLongitude(),
                altitudeText,
                accuracyText,
                speedText,
                bearingText,
                gpsLogTimeFormat.format(new Date(location.getTime())),
                getGpsLogPath());
        updateGpsStatusText(text);
    }

    private void startGpsAcquisitionFlow() {
        logGpsTrace("startGpsAcquisitionFlow: begin");
        if (locationManager == null) {
            updateGpsStatusText("GPS状態: LocationManager取得失敗\nログ: " + getGpsLogPath());
            logGpsTrace("LocationManager is null.");
            return;
        }
        int finePermission = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        int coarsePermission = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
        logGpsTrace("Permission check: fine=" + finePermission + ", coarse=" + coarsePermission);
        if (finePermission != PackageManager.PERMISSION_GRANTED) {
            updateGpsStatusText("GPS状態: 位置情報権限要求中...\nログ: " + getGpsLogPath());
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_LOCATION_PERMISSION);
            logGpsTrace("requestPermissions invoked.");
            return;
        }
        subscribeGpsUpdates();
    }

    private void subscribeGpsUpdates() {
        if (locationManager == null) {
            return;
        }
        try {
            boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            logGpsTrace("Provider status: gpsEnabled=" + gpsEnabled + ", networkEnabled=" + networkEnabled);
            if (!gpsEnabled) {
                updateGpsStatusText("GPS状態: GPSプロバイダが無効です\n位置情報設定を有効化してください\nログ: " + getGpsLogPath());
                return;
            }
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    gpsLocationListener);
            logGpsTrace("requestLocationUpdates registered for GPS provider.");

            Location lastKnownGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            logLocationDetails("lastKnownGps", lastKnownGps);
            if (lastKnownGps != null) {
                updateGpsDisplay(lastKnownGps, true);
            } else {
                updateGpsStatusText("GPS状態: 測位待機中...\nログ: " + getGpsLogPath());
            }
        } catch (SecurityException e) {
            logGpsTrace("subscribeGpsUpdates SecurityException: " + e.getMessage());
            Log.e(GPS_LOG_TAG, "Failed to subscribe GPS updates.", e);
            updateGpsStatusText("GPS状態: 位置情報アクセスエラー\nログ: " + getGpsLogPath());
        }
    }

    private void stopGpsUpdates() {
        if (locationManager == null) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            logGpsTrace("stopGpsUpdates skipped: missing ACCESS_FINE_LOCATION permission.");
            return;
        }
        try {
            locationManager.removeUpdates(gpsLocationListener);
            logGpsTrace("removeUpdates called for gpsLocationListener.");
        } catch (SecurityException e) {
            logGpsTrace("stopGpsUpdates SecurityException: " + e.getMessage());
            Log.e(GPS_LOG_TAG, "Failed to stop GPS updates.", e);
        }
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
        logGpsTrace("onResume called.");
        if (sensorManager != null) {
            if (pressureSensor != null) {
                sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_UI);
            }
            if (rotationSensor != null) {
                sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
            }
            if (gyroSensor != null) {
                sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_UI);
            }
        }
        startGpsAcquisitionFlow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        logGpsTrace("onPause called.");
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        stopGpsUpdates();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_PRESSURE) {
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
        } else if (type == Sensor.TYPE_ROTATION_VECTOR) {
            if (compassView != null && azimuthView != null && event.values != null) {
                float[] rotationMatrix = new float[9];
                float[] orientation = new float[3];
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
                SensorManager.getOrientation(rotationMatrix, orientation);
                float azimuth = (float) Math.toDegrees(orientation[0]);
                if (azimuth < 0) azimuth += 360;
                compassView.setAzimuth(azimuth);
                azimuthView.setText(String.format(Locale.getDefault(), "方位: %.0f°", azimuth));
            }
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            if (gyroView != null && event.values != null && event.values.length >= 3) {
                gyroView.setText(String.format(Locale.getDefault(),
                        "ジャイロ: x=%.3f y=%.3f z=%.3f (rad/s)",
                        event.values[0], event.values[1], event.values[2]));
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LOCATION_PERMISSION) {
            return;
        }
        logGpsTrace("onRequestPermissionsResult: permissions="
                + Arrays.toString(permissions)
                + ", grantResults="
                + Arrays.toString(grantResults));
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            updateGpsStatusText("GPS状態: 権限許可済み、測位開始\nログ: " + getGpsLogPath());
            subscribeGpsUpdates();
        } else {
            updateGpsStatusText("GPS状態: 権限が拒否されました\nログ: " + getGpsLogPath());
            logGpsTrace("Location permission denied by user.");
        }
    }
}
