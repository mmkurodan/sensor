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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
    private Sensor accelerometerSensor;
    private Sensor magneticSensor;
    private LocationManager locationManager;
    private TextView azimuthView;
    private TextView gyroView;
    private TextView gpsView;
    private CompassView compassView;
    private boolean gpsFixAcquired = false;
    private MapView mapView;
    private ItemizedIconOverlay<OverlayItem> gpsOverlay;
    private SensorInfoOverlay sensorInfoOverlay;
    private float[] accelerometerValues;
    private float[] magneticValues;
    private float[] gyroValues;
    private GeoPoint lastGpsLocation;
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
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }
        accelerometerValues = new float[3];
        magneticValues = new float[3];
        gyroValues = new float[3];

        // FrameLayout for fullscreen map with overlays
        FrameLayout mapContainer = new FrameLayout(this);
        mapContainer.setBackgroundColor(Color.parseColor("#121212"));

        // Fullscreen MapView
        mapView = new MapView(this);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        GeoPoint startPoint = new GeoPoint(35.6762, 139.6503);
        mapView.getController().setZoom(14);
        mapView.getController().setCenter(startPoint);

        List<OverlayItem> items = new ArrayList<>();
        gpsOverlay = new ItemizedIconOverlay<>(items, null, getApplicationContext());
        mapView.getOverlays().add(gpsOverlay);

        FrameLayout.LayoutParams mapParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        mapView.setLayoutParams(mapParams);
        mapContainer.addView(mapView);

        // Sensor info overlay (bottom sheet style - full width)
        sensorInfoOverlay = new SensorInfoOverlay(this);
        sensorInfoOverlay.setOnReturnToLocationClicked(new Runnable() {
            @Override
            public void run() {
                if (lastGpsLocation != null && mapView != null) {
                    mapView.getController().setCenter(lastGpsLocation);
                }
            }
        });
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        overlayParams.gravity = Gravity.BOTTOM;
        sensorInfoOverlay.setLayoutParams(overlayParams);
        mapContainer.addView(sensorInfoOverlay);

        setContentView(mapContainer);
        updateGpsStatusText("GPS状態: 初期化完了\n権限確認待ち...");
    }

    private void updateGpsStatusText(String text) {
        // GPS status is now displayed in the map tooltip or can be logged
    }



    private void updateGpsDisplay(Location location, boolean fromCache) {
        if (location == null) {
            return;
        }
        lastGpsLocation = new GeoPoint(location.getLatitude(), location.getLongitude());
        
        if (sensorInfoOverlay != null && location.hasAltitude()) {
            sensorInfoOverlay.setAltitude(location.getAltitude());
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
                "GPS: %s\n緯度: %.6f  経度: %.6f\n高度: %s  精度: %s  速度: %s  方位: %s",
                source,
                location.getLatitude(),
                location.getLongitude(),
                altitudeText,
                accuracyText,
                speedText,
                bearingText);
        
        if (mapView != null && gpsOverlay != null) {
            GeoPoint point = new GeoPoint(location.getLatitude(), location.getLongitude());
            gpsOverlay.removeAllItems();
            OverlayItem item = new OverlayItem("現在位置", "GPS取得", point);
            item.setMarker(ContextCompat.getDrawable(this, android.R.drawable.ic_menu_mylocation));
            gpsOverlay.addItem(item);
        }
        
        updateSensorInfoDisplay();
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
            if (accelerometerSensor != null) {
                sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_UI);
            }
            if (magneticSensor != null) {
                sensorManager.registerListener(this, magneticSensor, SensorManager.SENSOR_DELAY_UI);
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
            if (event.values != null) {
                float[] rotationMatrix = new float[9];
                float[] orientation = new float[3];
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
                SensorManager.getOrientation(rotationMatrix, orientation);
                float azimuth = (float) Math.toDegrees(orientation[0]);
                if (azimuth < 0) azimuth += 360;
                if (compassView != null) {
                    compassView.setAzimuth(azimuth);
                }
                if (sensorInfoOverlay != null) {
                    sensorInfoOverlay.setCompassAzimuth(azimuth);
                }
                updateSensorInfoDisplay();
            }
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            if (event.values != null && event.values.length >= 3) {
                gyroValues[0] = event.values[0];
                gyroValues[1] = event.values[1];
                gyroValues[2] = event.values[2];
                updateSensorInfoDisplay();
            }
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            if (event.values != null && event.values.length >= 3) {
                accelerometerValues[0] = event.values[0];
                accelerometerValues[1] = event.values[1];
                accelerometerValues[2] = event.values[2];
                updateSensorInfoDisplay();
            }
        } else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
            if (event.values != null && event.values.length >= 3) {
                magneticValues[0] = event.values[0];
                magneticValues[1] = event.values[1];
                magneticValues[2] = event.values[2];
                updateSensorInfoDisplay();
            }
        }
    }

    private void updateSensorInfoDisplay() {
        if (sensorInfoOverlay == null) {
            return;
        }

        List<String> sensorLines = new ArrayList<>();

        // GPS Info (if available)
        if (lastGpsLocation != null) {
            sensorLines.add(String.format(Locale.getDefault(),
                    "位置情報: %.6f, %.6f",
                    lastGpsLocation.getLatitude(),
                    lastGpsLocation.getLongitude()));
        } else {
            sensorLines.add("位置情報: 取得待機中");
        }

        // Accelerometer
        if (accelerometerSensor != null) {
            sensorLines.add(String.format(Locale.getDefault(),
                    "加速度: X=%.2f Y=%.2f Z=%.2f m/s²",
                    accelerometerValues[0], accelerometerValues[1], accelerometerValues[2]));
        }

        // Magnetic field
        if (magneticSensor != null) {
            sensorLines.add(String.format(Locale.getDefault(),
                    "磁場: X=%.1f Y=%.1f Z=%.1f µT",
                    magneticValues[0], magneticValues[1], magneticValues[2]));
        }

        // Gyroscope
        if (gyroSensor != null) {
            sensorInfoOverlay.setGyroValues(gyroValues[0], gyroValues[1], gyroValues[2]);
        }

        sensorInfoOverlay.setSensorInfo(sensorLines);
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
