package com.example.sensor;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.ItemizedIconOverlay;
import org.osmdroid.views.overlay.OverlayItem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {

    private static final int REQUEST_LOCATION_PERMISSION = 1001;
    private static final GeoPoint DEFAULT_START_POINT = new GeoPoint(35.6762, 139.6503);

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private Sensor gyroSensor;
    private Sensor accelerometerSensor;
    private Sensor magneticSensor;
    private LocationManager locationManager;
    private MapView mapView;
    private ItemizedIconOverlay<OverlayItem> gpsOverlay;
    private ItemizedIconOverlay<OverlayItem> searchOverlay;
    private SensorInfoOverlay sensorInfoOverlay;
    private OnlineAddressGeocoder onlineAddressGeocoder;
    private EditText addressInput;
    private float[] accelerometerValues;
    private float[] magneticValues;
    private float[] gyroValues;
    private GeoPoint lastGpsLocation;
    private GeoPoint lastSearchLocation;

    private final LocationListener gpsLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            updateGpsDisplay(location, false);
        }

        @Override
        public void onProviderEnabled(String provider) {
            // Not used.
        }

        @Override
        public void onProviderDisabled(String provider) {
            showToast("GPSプロバイダが無効です");
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            // Not used.
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        configureOsmdroid();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        onlineAddressGeocoder = new OnlineAddressGeocoder(this);

        if (sensorManager != null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }

        accelerometerValues = new float[3];
        magneticValues = new float[3];
        gyroValues = new float[3];

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#121212"));

        mapView = createMapView();
        root.addView(mapView);
        root.addView(createSearchPanel());

        sensorInfoOverlay = new SensorInfoOverlay(this);
        sensorInfoOverlay.setOnReturnToLocationClicked(() -> {
            if (lastGpsLocation != null && mapView != null) {
                mapView.getController().setCenter(lastGpsLocation);
            }
        });
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        overlayParams.gravity = Gravity.BOTTOM;
        sensorInfoOverlay.setLayoutParams(overlayParams);
        root.addView(sensorInfoOverlay);

        setContentView(root);
    }

    private void configureOsmdroid() {
        Configuration.getInstance().setUserAgentValue(getApplicationContext().getPackageName());

        java.io.File osmdroidDir = new java.io.File(getFilesDir(), "osmdroid");
        if (!osmdroidDir.exists()) {
            osmdroidDir.mkdirs();
        }
        Configuration.getInstance().setOsmdroidBasePath(osmdroidDir);

        java.io.File tileCache = new java.io.File(osmdroidDir, "tiles");
        if (!tileCache.exists()) {
            tileCache.mkdirs();
        }
        Configuration.getInstance().setOsmdroidTileCache(tileCache);
    }

    private MapView createMapView() {
        MapView createdMapView = new MapView(this);
        createdMapView.setTileSource(TileSourceFactory.MAPNIK);
        createdMapView.setUseDataConnection(true);
        createdMapView.setMultiTouchControls(true);
        createdMapView.setBackgroundColor(Color.parseColor("#101820"));
        createdMapView.getController().setZoom(6);
        createdMapView.getController().setCenter(DEFAULT_START_POINT);

        createdMapView.getOverlays().add(new CoordinateGridOverlay(this));

        gpsOverlay = new ItemizedIconOverlay<>(new ArrayList<>(), null, getApplicationContext());
        searchOverlay = new ItemizedIconOverlay<>(new ArrayList<>(), null, getApplicationContext());
        createdMapView.getOverlays().add(gpsOverlay);
        createdMapView.getOverlays().add(searchOverlay);

        FrameLayout.LayoutParams mapParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        createdMapView.setLayoutParams(mapParams);
        return createdMapView;
    }

    private LinearLayout createSearchPanel() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.parseColor("#CC121212"));
        container.setPadding(dp(12), dp(12), dp(12), dp(12));

        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        containerParams.gravity = Gravity.TOP;
        containerParams.topMargin = dp(12);
        containerParams.leftMargin = dp(12);
        containerParams.rightMargin = dp(12);
        container.setLayoutParams(containerParams);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);

        addressInput = new EditText(this);
        addressInput.setHint("住所を検索");
        addressInput.setSingleLine(true);
        addressInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        addressInput.setBackgroundColor(Color.WHITE);
        addressInput.setTextColor(Color.BLACK);
        addressInput.setHintTextColor(Color.DKGRAY);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        inputParams.rightMargin = dp(8);
        addressInput.setLayoutParams(inputParams);
        addressInput.setOnEditorActionListener((view, actionId, event) -> {
            boolean enterPressed = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enterPressed) {
                performAddressSearch();
                return true;
            }
            return false;
        });

        Button searchButton = new Button(this);
        searchButton.setText("検索");
        searchButton.setOnClickListener(view -> performAddressSearch());

        inputRow.addView(addressInput);
        inputRow.addView(searchButton);
        container.addView(inputRow);
        return container;
    }

    private void performAddressSearch() {
        if (addressInput == null) {
            return;
        }

        String query = addressInput.getText().toString().trim();
        if (query.isEmpty()) {
            addressInput.setError("住所を入力してください。");
            return;
        }

        addressInput.setError(null);
        hideKeyboard();

        new Thread(() -> {
            try {
                OnlineAddressGeocoder.SearchResult result = onlineAddressGeocoder.search(query);
                runOnUiThread(() -> applySearchResult(result));
            } catch (IOException e) {
                runOnUiThread(() -> showToast("住所検索に失敗しました"));
            }
        }).start();
    }

    private void applySearchResult(OnlineAddressGeocoder.SearchResult result) {
        if (result == null) {
            if (searchOverlay != null) {
                searchOverlay.removeAllItems();
            }
            if (mapView != null) {
                mapView.invalidate();
            }
            showToast("該当する住所が見つかりませんでした");
            return;
        }
        if (mapView == null || searchOverlay == null) {
            showToast("地図の初期化が完了していません");
            return;
        }

        lastSearchLocation = new GeoPoint(result.getLatitude(), result.getLongitude());

        searchOverlay.removeAllItems();
        OverlayItem item = new OverlayItem(
                result.getDisplayName(),
                String.format(Locale.getDefault(), "緯度: %.6f  経度: %.6f", result.getLatitude(), result.getLongitude()),
                lastSearchLocation);
        item.setMarker(ContextCompat.getDrawable(this, android.R.drawable.ic_menu_search));
        searchOverlay.addItem(item);

        mapView.getController().setZoom(16);
        mapView.getController().setCenter(lastSearchLocation);
        mapView.invalidate();
    }

    private void hideKeyboard() {
        InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (inputMethodManager != null && addressInput != null) {
            inputMethodManager.hideSoftInputFromWindow(addressInput.getWindowToken(), 0);
        }
        if (addressInput != null) {
            addressInput.clearFocus();
        }
    }

    private void updateGpsDisplay(Location location, boolean fromCache) {
        if (location == null) {
            return;
        }

        lastGpsLocation = new GeoPoint(location.getLatitude(), location.getLongitude());
        if (sensorInfoOverlay != null && location.hasAltitude()) {
            sensorInfoOverlay.setAltitude(location.getAltitude());
        }

        if (gpsOverlay != null) {
            gpsOverlay.removeAllItems();
            OverlayItem item = new OverlayItem("現在位置", fromCache ? "GPSキャッシュ" : "GPS取得", lastGpsLocation);
            item.setMarker(ContextCompat.getDrawable(this, android.R.drawable.ic_menu_mylocation));
            gpsOverlay.addItem(item);
        }

        if (mapView != null && lastSearchLocation == null) {
            mapView.getController().setCenter(lastGpsLocation);
        }

        updateSensorInfoDisplay();
        if (mapView != null) {
            mapView.invalidate();
        }
    }

    private void startGpsAcquisitionFlow() {
        if (locationManager == null) {
            return;
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
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
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                showToast("GPSプロバイダが無効です");
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
            }
        } catch (SecurityException e) {
            showToast("位置情報にアクセスできません");
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
            showToast("GPS更新の停止に失敗しました");
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
    protected void onDestroy() {
        super.onDestroy();
        if (mapView != null) {
            mapView.onDetach();
        }
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
                if (azimuth < 0) {
                    azimuth += 360f;
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
        if (lastGpsLocation != null) {
            sensorLines.add(String.format(
                    Locale.getDefault(),
                    "緯度: %.6f  経度: %.6f",
                    lastGpsLocation.getLatitude(),
                    lastGpsLocation.getLongitude()));
        } else {
            sensorLines.add("緯度: --  経度: --");
        }

        if (accelerometerSensor != null) {
            sensorLines.add(String.format(
                    Locale.getDefault(),
                    "加速度: X=%.2f Y=%.2f Z=%.2f m/s²",
                    accelerometerValues[0],
                    accelerometerValues[1],
                    accelerometerValues[2]));
        }

        if (magneticSensor != null) {
            sensorLines.add(String.format(
                    Locale.getDefault(),
                    "磁場: X=%.1f Y=%.1f Z=%.1f µT",
                    magneticValues[0],
                    magneticValues[1],
                    magneticValues[2]));
        }

        if (gyroSensor != null) {
            sensorInfoOverlay.setGyroValues(gyroValues[0], gyroValues[1], gyroValues[2]);
        }

        sensorInfoOverlay.setSensorInfo(sensorLines);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used.
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LOCATION_PERMISSION) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            subscribeGpsUpdates();
        } else {
            showToast("位置情報権限が拒否されました");
        }
    }

    private void showToast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
