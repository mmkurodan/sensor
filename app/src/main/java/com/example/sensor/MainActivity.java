package com.example.sensor;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Point;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {

    private static final int REQUEST_LOCATION_PERMISSION = 1001;
    private static final int SEARCH_RESULT_LIMIT = 20;
    private static final float MAX_COMPASS_TILT_DEGREES = 70f;
    private static final float AZIMUTH_SMOOTHING_FACTOR = 0.18f;
    private static final GeoPoint DEFAULT_START_POINT = new GeoPoint(35.6762, 139.6503);

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private Sensor gyroSensor;
    private Sensor accelerometerSensor;
    private Sensor magneticSensor;
    private LocationManager locationManager;
    private MapView mapView;
    private Marker gpsMarker;
    private Marker searchMarker;
    private Polyline routePolyline;
    private SensorInfoOverlay sensorInfoOverlay;
    private OnlineAddressGeocoder onlineAddressGeocoder;
    private OnlineRouteService onlineRouteService;
    private EditText addressInput;
    private LinearLayout searchResultsContainer;
    private ScrollView searchResultsScrollView;
    private final float[] accelerometerValues = new float[3];
    private final float[] magneticValues = new float[3];
    private final float[] gyroValues = new float[3];
    private final float[] rawRotationMatrix = new float[9];
    private final float[] adjustedRotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];
    private boolean hasAccelerometerReading;
    private boolean hasMagneticReading;
    private boolean trackingEnabled = true;
    private boolean centeringEnabled = true;
    private float smoothedAzimuth = Float.NaN;
    private GeoPoint lastGpsLocation;
    private GeoPoint lastSearchLocation;
    private String routeSummaryLine;

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
        onlineRouteService = new OnlineRouteService(this);

        if (sensorManager != null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#121212"));

        mapView = createMapView();
        root.addView(mapView);
        root.addView(createSearchPanel());

        sensorInfoOverlay = new SensorInfoOverlay(this);
        sensorInfoOverlay.setOnReturnToLocationClicked(this::recenterOnCurrentLocation);
        sensorInfoOverlay.setOnTrackingCenteringToggleClicked(this::toggleTrackingAndCentering);
        sensorInfoOverlay.setOnPanelVisibilityChanged(() -> {
            if (centeringEnabled && lastGpsLocation != null) {
                centerMapOnCurrentLocation();
            }
        });
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        sensorInfoOverlay.setLayoutParams(overlayParams);
        root.addView(sensorInfoOverlay);

        setContentView(root);
        updateTrackingCenteringToggle();
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
        createdMapView.getController().setZoom(6.0);
        createdMapView.getController().setCenter(DEFAULT_START_POINT);

        routePolyline = new Polyline();
        routePolyline.setColor(Color.parseColor("#4FC3F7"));
        routePolyline.setWidth(dp(4));

        gpsMarker = new Marker(createdMapView);
        gpsMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        gpsMarker.setIcon(ContextCompat.getDrawable(this, android.R.drawable.ic_menu_mylocation));
        gpsMarker.setTitle("現在位置");
        gpsMarker.setInfoWindow(null);
        gpsMarker.setVisible(false);

        searchMarker = new Marker(createdMapView);
        searchMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        searchMarker.setIcon(ContextCompat.getDrawable(this, android.R.drawable.ic_menu_search));
        searchMarker.setTitle("検索地点");
        searchMarker.setInfoWindow(null);
        searchMarker.setVisible(false);

        createdMapView.getOverlays().add(new CoordinateGridOverlay(this));
        createdMapView.getOverlays().add(routePolyline);
        createdMapView.getOverlays().add(searchMarker);
        createdMapView.getOverlays().add(gpsMarker);

        FrameLayout.LayoutParams mapParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
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
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
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
        searchButton.setAllCaps(false);
        searchButton.setText("検索");
        searchButton.setOnClickListener(view -> performAddressSearch());

        inputRow.addView(addressInput);
        inputRow.addView(searchButton);
        container.addView(inputRow);

        searchResultsContainer = new LinearLayout(this);
        searchResultsContainer.setOrientation(LinearLayout.VERTICAL);

        searchResultsScrollView = new ScrollView(this);
        searchResultsScrollView.setVisibility(View.GONE);
        LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(220)
        );
        resultParams.topMargin = dp(8);
        searchResultsScrollView.setLayoutParams(resultParams);
        searchResultsScrollView.addView(searchResultsContainer);
        container.addView(searchResultsScrollView);

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
                List<OnlineAddressGeocoder.SearchResult> results = onlineAddressGeocoder.search(query, SEARCH_RESULT_LIMIT);
                runOnUiThread(() -> showSearchCandidates(results));
            } catch (IOException e) {
                runOnUiThread(() -> {
                    hideSearchCandidates();
                    showToast("住所検索に失敗しました");
                });
            }
        }).start();
    }

    private void showSearchCandidates(List<OnlineAddressGeocoder.SearchResult> results) {
        if (searchResultsContainer == null || searchResultsScrollView == null) {
            return;
        }

        searchResultsContainer.removeAllViews();
        clearRoute();

        if (results == null || results.isEmpty()) {
            searchResultsScrollView.setVisibility(View.GONE);
            showToast("該当する住所が見つかりませんでした");
            return;
        }

        TextView headerView = new TextView(this);
        headerView.setText("候補から選択");
        headerView.setTextColor(Color.parseColor("#4FC3F7"));
        headerView.setTextSize(12f);
        headerView.setPadding(dp(4), dp(2), dp(4), dp(6));
        searchResultsContainer.addView(headerView);

        for (OnlineAddressGeocoder.SearchResult result : results) {
            TextView candidateView = new TextView(this);
            candidateView.setText(result.getDisplayName());
            candidateView.setTextColor(Color.WHITE);
            candidateView.setTextSize(14f);
            candidateView.setBackgroundColor(Color.parseColor("#33212121"));
            candidateView.setPadding(dp(12), dp(10), dp(12), dp(10));
            candidateView.setMaxLines(3);
            candidateView.setEllipsize(TextUtils.TruncateAt.END);

            LinearLayout.LayoutParams candidateParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            candidateParams.bottomMargin = dp(6);
            candidateView.setLayoutParams(candidateParams);
            candidateView.setOnClickListener(view -> applySearchSelection(result));
            searchResultsContainer.addView(candidateView);
        }

        searchResultsScrollView.setVisibility(View.VISIBLE);
    }

    private void hideSearchCandidates() {
        if (searchResultsContainer != null) {
            searchResultsContainer.removeAllViews();
        }
        if (searchResultsScrollView != null) {
            searchResultsScrollView.setVisibility(View.GONE);
        }
    }

    private void applySearchSelection(OnlineAddressGeocoder.SearchResult result) {
        if (result == null) {
            return;
        }
        if (mapView == null || searchMarker == null) {
            showToast("地図の初期化が完了していません");
            return;
        }

        hideSearchCandidates();
        addressInput.setText(result.getDisplayName());
        lastSearchLocation = new GeoPoint(result.getLatitude(), result.getLongitude());

        searchMarker.setPosition(lastSearchLocation);
        searchMarker.setTitle(result.getDisplayName());
        searchMarker.setSubDescription(String.format(
                Locale.getDefault(),
                "緯度: %.6f  経度: %.6f",
                result.getLatitude(),
                result.getLongitude()
        ));
        searchMarker.setVisible(true);

        if (trackingEnabled || centeringEnabled) {
            setTrackingAndCenteringEnabled(false);
            stopGpsUpdates();
        }

        if (lastGpsLocation != null) {
            fetchRouteToSearchLocation(lastGpsLocation, lastSearchLocation);
        } else {
            clearRoute();
            mapView.getController().setZoom(16.0);
            mapView.getController().setCenter(lastSearchLocation);
            mapView.invalidate();
        }
    }

    private void fetchRouteToSearchLocation(GeoPoint start, GeoPoint destination) {
        new Thread(() -> {
            try {
                OnlineRouteService.RouteResult routeResult = onlineRouteService.fetchRoute(start, destination);
                runOnUiThread(() -> applyRouteResult(routeResult, destination));
            } catch (IOException e) {
                runOnUiThread(() -> {
                    clearRoute();
                    mapView.getController().setZoom(16.0);
                    mapView.getController().setCenter(destination);
                    mapView.invalidate();
                    showToast("ルート取得に失敗しました");
                });
            }
        }).start();
    }

    private void applyRouteResult(OnlineRouteService.RouteResult routeResult, GeoPoint fallbackDestination) {
        if (mapView == null || routePolyline == null) {
            return;
        }

        if (routeResult == null || routeResult.getPoints().isEmpty()) {
            clearRoute();
            mapView.getController().setZoom(16.0);
            mapView.getController().setCenter(fallbackDestination);
            mapView.invalidate();
            showToast("ルートが見つかりませんでした");
            return;
        }

        routePolyline.setPoints(routeResult.getPoints());
        routeSummaryLine = formatRouteSummary(routeResult);
        updateSensorInfoDisplay();

        BoundingBox boundingBox = createBoundingBox(routeResult.getPoints());
        if (boundingBox != null) {
            mapView.zoomToBoundingBox(boundingBox, true);
        }
        mapView.invalidate();
    }

    private void clearRoute() {
        routeSummaryLine = null;
        if (routePolyline != null) {
            routePolyline.setPoints(new ArrayList<>());
        }
        updateSensorInfoDisplay();
        if (mapView != null) {
            mapView.invalidate();
        }
    }

    private BoundingBox createBoundingBox(List<GeoPoint> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }

        double north = -90d;
        double south = 90d;
        double east = -180d;
        double west = 180d;

        for (GeoPoint point : points) {
            north = Math.max(north, point.getLatitude());
            south = Math.min(south, point.getLatitude());
            east = Math.max(east, point.getLongitude());
            west = Math.min(west, point.getLongitude());
        }
        return new BoundingBox(north, east, south, west);
    }

    private String formatRouteSummary(OnlineRouteService.RouteResult routeResult) {
        double distanceKm = routeResult.getDistanceMeters() / 1000d;
        if (routeResult.isFallback()) {
            return String.format(Locale.getDefault(), "直線距離: %.1f km", distanceKm);
        }
        long durationMinutes = Math.round(routeResult.getDurationSeconds() / 60d);
        if (Double.isNaN(distanceKm) || routeResult.getDistanceMeters() <= 0d) {
            return "ルート: 取得済み";
        }
        return String.format(Locale.getDefault(), "ルート: %.1f km / 約%d分", distanceKm, durationMinutes);
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

        if (gpsMarker != null) {
            gpsMarker.setPosition(lastGpsLocation);
            gpsMarker.setSubDescription(fromCache ? "GPSキャッシュ" : "GPS取得");
            gpsMarker.setVisible(true);
        }

        if (mapView != null && centeringEnabled) {
            centerMapOnCurrentLocation();
        }

        updateSensorInfoDisplay();
        if (mapView != null) {
            mapView.invalidate();
        }
    }

    private void recenterOnCurrentLocation() {
        if (lastGpsLocation == null) {
            showToast("現在地の取得を待っています");
            return;
        }
        centerMapOnCurrentLocation();
    }

    private void centerMapOnCurrentLocation() {
        centerMap(lastGpsLocation, sensorInfoOverlay != null && sensorInfoOverlay.isVisible());
    }

    private void centerMap(GeoPoint target, boolean keepInTopVisibleArea) {
        if (mapView == null || target == null) {
            return;
        }

        mapView.post(() -> {
            if (mapView == null) {
                return;
            }

            if (!keepInTopVisibleArea || sensorInfoOverlay == null || sensorInfoOverlay.getOccupiedBottomInset() <= 0f) {
                mapView.getController().setCenter(target);
                mapView.invalidate();
                return;
            }

            Projection projection = mapView.getProjection();
            if (projection == null) {
                mapView.getController().setCenter(target);
                mapView.invalidate();
                return;
            }

            Point targetPoint = projection.toPixels(target, null);
            int mapCenterX = mapView.getWidth() / 2;
            int mapCenterY = mapView.getHeight() / 2;
            int desiredY = Math.round((mapView.getHeight() - sensorInfoOverlay.getOccupiedBottomInset()) / 2f);
            int dx = targetPoint.x - mapCenterX;
            int dy = targetPoint.y - desiredY;

            IGeoPoint adjustedCenter = projection.fromPixels(mapCenterX + dx, mapCenterY + dy);
            if (adjustedCenter != null) {
                mapView.getController().setCenter(new GeoPoint(adjustedCenter.getLatitude(), adjustedCenter.getLongitude()));
            } else {
                mapView.getController().setCenter(target);
            }
            mapView.invalidate();
        });
    }

    private void toggleTrackingAndCentering() {
        boolean nextEnabled = !trackingEnabled || !centeringEnabled;
        setTrackingAndCenteringEnabled(nextEnabled);
        if (!nextEnabled) {
            stopGpsUpdates();
            return;
        }

        startGpsAcquisitionFlow();
        if (centeringEnabled && lastGpsLocation != null) {
            centerMapOnCurrentLocation();
        }
    }

    private void setTrackingAndCenteringEnabled(boolean enabled) {
        trackingEnabled = enabled;
        centeringEnabled = enabled;
        updateTrackingCenteringToggle();
    }

    private void updateTrackingCenteringToggle() {
        if (sensorInfoOverlay != null) {
            sensorInfoOverlay.setTrackingCenteringEnabled(trackingEnabled && centeringEnabled);
        }
    }

    private void startGpsAcquisitionFlow() {
        if (!trackingEnabled || locationManager == null) {
            return;
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_LOCATION_PERMISSION
            );
            return;
        }

        subscribeGpsUpdates();
    }

    private void subscribeGpsUpdates() {
        if (!trackingEnabled || locationManager == null) {
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
                    gpsLocationListener
            );

            Location lastKnownGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastKnownGps != null) {
                updateGpsDisplay(lastKnownGps, true);
            }
        } catch (SecurityException e) {
            setTrackingAndCenteringEnabled(false);
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
                SensorManager.getRotationMatrixFromVector(rawRotationMatrix, event.values);
                updateCompassAzimuth(rawRotationMatrix);
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
                hasAccelerometerReading = true;
                updateSensorInfoDisplay();
                if (rotationSensor == null && hasMagneticReading) {
                    updateCompassFromAccelerometerAndMagneticField();
                }
            }
        } else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
            if (event.values != null && event.values.length >= 3) {
                magneticValues[0] = event.values[0];
                magneticValues[1] = event.values[1];
                magneticValues[2] = event.values[2];
                hasMagneticReading = true;
                updateSensorInfoDisplay();
                if (rotationSensor == null && hasAccelerometerReading) {
                    updateCompassFromAccelerometerAndMagneticField();
                }
            }
        }
    }

    private void updateCompassFromAccelerometerAndMagneticField() {
        if (SensorManager.getRotationMatrix(rawRotationMatrix, null, accelerometerValues, magneticValues)) {
            updateCompassAzimuth(rawRotationMatrix);
        }
    }

    private void updateCompassAzimuth(float[] sourceRotationMatrix) {
        remapRotationMatrixForDisplay(sourceRotationMatrix, adjustedRotationMatrix);
        SensorManager.getOrientation(adjustedRotationMatrix, orientationAngles);

        float pitch = (float) Math.toDegrees(orientationAngles[1]);
        float roll = (float) Math.toDegrees(orientationAngles[2]);
        if (Math.abs(pitch) > MAX_COMPASS_TILT_DEGREES || Math.abs(roll) > MAX_COMPASS_TILT_DEGREES) {
            return;
        }

        float azimuth = (float) Math.toDegrees(orientationAngles[0]);
        azimuth = normalizeAzimuth(360f - azimuth);

        smoothedAzimuth = smoothAzimuth(smoothedAzimuth, azimuth);
        if (sensorInfoOverlay != null) {
            sensorInfoOverlay.setCompassAzimuth(smoothedAzimuth);
        }
        updateSensorInfoDisplay();
    }

    private void remapRotationMatrixForDisplay(float[] sourceRotationMatrix, float[] outRotationMatrix) {
        int axisX = SensorManager.AXIS_X;
        int axisY = SensorManager.AXIS_Y;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();

        switch (rotation) {
            case Surface.ROTATION_90:
                axisX = SensorManager.AXIS_Y;
                axisY = SensorManager.AXIS_MINUS_X;
                break;
            case Surface.ROTATION_180:
                axisX = SensorManager.AXIS_MINUS_X;
                axisY = SensorManager.AXIS_MINUS_Y;
                break;
            case Surface.ROTATION_270:
                axisX = SensorManager.AXIS_MINUS_Y;
                axisY = SensorManager.AXIS_X;
                break;
            case Surface.ROTATION_0:
            default:
                break;
        }

        SensorManager.remapCoordinateSystem(sourceRotationMatrix, axisX, axisY, outRotationMatrix);
    }

    private float smoothAzimuth(float previousAzimuth, float nextAzimuth) {
        if (Float.isNaN(previousAzimuth)) {
            return nextAzimuth;
        }
        float delta = ((nextAzimuth - previousAzimuth + 540f) % 360f) - 180f;
        float smoothed = previousAzimuth + (delta * AZIMUTH_SMOOTHING_FACTOR);
        if (smoothed < 0f) {
            smoothed += 360f;
        } else if (smoothed >= 360f) {
            smoothed -= 360f;
        }
        return smoothed;
    }

    private float normalizeAzimuth(float azimuth) {
        float normalized = azimuth % 360f;
        if (normalized < 0f) {
            normalized += 360f;
        }
        return normalized;
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
                    lastGpsLocation.getLongitude()
            ));
        } else {
            sensorLines.add("緯度: --  経度: --");
        }

        if (routeSummaryLine != null) {
            sensorLines.add(routeSummaryLine);
        }

        if (accelerometerSensor != null) {
            sensorLines.add(String.format(
                    Locale.getDefault(),
                    "加速度: X=%.2f Y=%.2f Z=%.2f m/s²",
                    accelerometerValues[0],
                    accelerometerValues[1],
                    accelerometerValues[2]
            ));
        }

        if (magneticSensor != null) {
            sensorLines.add(String.format(
                    Locale.getDefault(),
                    "磁場: X=%.1f Y=%.1f Z=%.1f µT",
                    magneticValues[0],
                    magneticValues[1],
                    magneticValues[2]
            ));
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
            if (trackingEnabled) {
                subscribeGpsUpdates();
            }
        } else {
            setTrackingAndCenteringEnabled(false);
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
