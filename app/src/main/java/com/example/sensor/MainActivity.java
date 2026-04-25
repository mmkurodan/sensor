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
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.ItemizedIconOverlay;
import org.osmdroid.views.overlay.OverlayItem;
import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements SensorEventListener {

    private static final int REQUEST_LOCATION_PERMISSION = 1001;

    private SensorManager sensorManager;
    private final Map<Integer, TextView> sensorValueViews = new HashMap<>();
    private Sensor rotationSensor;
    private Sensor gyroSensor;
    private LocationManager locationManager;
    private TextView azimuthView;
    private TextView gyroView;
    private TextView gpsView;
    private CompassView compassView;
    private boolean gpsFixAcquired = false;
    private MapView mapView;
    private ItemizedIconOverlay<OverlayItem> gpsOverlay;
    private final LocationListener gpsLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (!gpsFixAcquired && LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
                gpsFixAcquired = true;
            }
            updateGpsDisplay(location, false);
        }

        @Override
        public void onProviderEnabled(String provider) {
            updateGpsStatusText("GPS状態: プロバイダ有効化 (" + provider + ")\n取得待機中...");
        }

        @Override
        public void onProviderDisabled(String provider) {
            updateGpsStatusText("GPS状態: プロバイダ無効 (" + provider + ")");
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            // logging removed
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Configuration.getInstance().setUserAgentValue(getApplicationContext().getPackageName());

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (sensorManager != null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }

        LinearLayout mainContainer = new LinearLayout(this);
        mainContainer.setOrientation(LinearLayout.VERTICAL);
        mainContainer.setBackgroundColor(Color.parseColor("#121212"));

        mapView = new MapView(this);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        GeoPoint startPoint = new GeoPoint(35.6762, 139.6503);
        mapView.getController().setZoom(14);
        mapView.getController().setCenter(startPoint);

        List<OverlayItem> items = new ArrayList<>();
        gpsOverlay = new ItemizedIconOverlay<>(items, null, getApplicationContext());
        mapView.getOverlays().add(gpsOverlay);

        LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 400);
        mapView.setLayoutParams(mapParams);
        mainContainer.addView(mapView);

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

        scrollView.addView(mainLayout);

        mainContainer.addView(scrollView);
        setContentView(mainContainer);
        updateGpsStatusText("GPS状態: 初期化完了\n権限確認待ち...");
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
        gpsView.setText("GPS状態: 初期化中...");
        gpsView.setTextSize(14);
        gpsView.setTextColor(Color.parseColor("#76FF03"));
        gpsView.setTypeface(Typeface.MONOSPACE);
        card.addView(gpsView);

        return card;
    }



    private void updateGpsStatusText(String text) {
        if (gpsView != null) {
            gpsView.setText(text);
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
                "GPS状態: %s\nプロバイダ: %s\n緯度: %.6f\n経度: %.6f\n高度: %s\n精度: %s\n速度: %s\n方位: %s\n時刻: %s",
                source,
                location.getProvider(),
                location.getLatitude(),
                location.getLongitude(),
                altitudeText,
                accuracyText,
                speedText,
                bearingText,
                String.valueOf(location.getTime()));
        updateGpsStatusText(text);

        if (mapView != null && gpsOverlay != null) {
            GeoPoint point = new GeoPoint(location.getLatitude(), location.getLongitude());
            gpsOverlay.removeAllItems();
            OverlayItem item = new OverlayItem("現在位置", "GPS取得", point);
            item.setMarker(ContextCompat.getDrawable(this, android.R.drawable.ic_menu_mylocation));
            gpsOverlay.addItem(item);
            mapView.getController().setCenter(point);
        }
    }

    private void startGpsAcquisitionFlow() {
        if (locationManager == null) {
            updateGpsStatusText("GPS状態: LocationManager取得失敗");
            return;
        }
        int finePermission = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        int coarsePermission = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (finePermission != PackageManager.PERMISSION_GRANTED) {
            updateGpsStatusText("GPS状態: 位置情報権限要求中...");
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_LOCATION_PERMISSION);
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
            if (!gpsEnabled) {
                updateGpsStatusText("GPS状態: GPSプロバイダが無効です\n位置情報設定を有効化してください");
                return;
            }
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    gpsLocationListener);

            Location lastKnownGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastKnownGps != null) {
                updateGpsDisplay(lastKnownGps, true);
            } else {
                updateGpsStatusText("GPS状態: 測位待機中...");
            }
        } catch (SecurityException e) {
            updateGpsStatusText("GPS状態: 位置情報アクセスエラー");
        }
    }

    private void stopGpsUpdates() {
        if (locationManager == null) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            locationManager.removeUpdates(gpsLocationListener);
        } catch (SecurityException e) {
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
        if (mapView != null) {
            mapView.onResume();
        }
        if (sensorManager != null) {
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
        if (mapView != null) {
            mapView.onPause();
        }
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        stopGpsUpdates();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_ROTATION_VECTOR) {
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
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            updateGpsStatusText("GPS状態: 権限許可済み、測位開始");
            subscribeGpsUpdates();
        } else {
            updateGpsStatusText("GPS状態: 権限が拒否されました");
        }
    }
}
