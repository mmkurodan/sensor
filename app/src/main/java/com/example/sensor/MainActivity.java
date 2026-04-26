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
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.osmdroid.config.Configuration;
import org.osmdroid.mapsforge.MapsForgeTileProvider;
import org.osmdroid.mapsforge.MapsForgeTileSource;
import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.ItemizedIconOverlay;
import org.osmdroid.views.overlay.OverlayItem;

import java.io.File;
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
    private OfflineDataRepository offlineDataRepository;
    private MapView mapView;
    private MapsForgeTileSource mapsForgeTileSource;
    private ItemizedIconOverlay<OverlayItem> gpsOverlay;
    private ItemizedIconOverlay<OverlayItem> searchOverlay;
    private SensorInfoOverlay sensorInfoOverlay;
    private OfflineAddressGeocoder offlineAddressGeocoder;
    private EditText addressInput;
    private Button mapDownloadButton;
    private Button addressDownloadButton;
    private TextView statusView;
    private float[] accelerometerValues;
    private float[] magneticValues;
    private float[] gyroValues;
    private GeoPoint lastGpsLocation;
    private GeoPoint lastSearchLocation;
    private String mapStatusMessage = "";
    private String addressStatusMessage = "";
    private String searchStatusMessage = "";
    private String gpsStatusMessage = "";
    private volatile boolean mapDownloadInProgress;
    private volatile boolean addressDownloadInProgress;

    private final LocationListener gpsLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            updateGpsDisplay(location, false);
        }

        @Override
        public void onProviderEnabled(String provider) {
            updateGpsStatusText("GPS状態: プロバイダ有効化 (" + provider + ")");
        }

        @Override
        public void onProviderDisabled(String provider) {
            updateGpsStatusText("GPS状態: プロバイダ無効 (" + provider + ")");
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
        offlineDataRepository = new OfflineDataRepository(this);
        offlineAddressGeocoder = new OfflineAddressGeocoder(this, offlineDataRepository);

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
        refreshMapProvider();
        refreshOfflineDataStatus();
        updateSearchStatus("検索状態: 住所を入力してオフライン検索できます。");
        updateGpsStatusText("GPS状態: 権限確認待ち...");
    }

    private void configureOsmdroid() {
        Configuration.getInstance().setUserAgentValue(getApplicationContext().getPackageName());

        File osmdroidDir = new File(getFilesDir(), "osmdroid");
        if (!osmdroidDir.exists()) {
            osmdroidDir.mkdirs();
        }
        Configuration.getInstance().setOsmdroidBasePath(osmdroidDir);

        File tileCache = new File(osmdroidDir, "tiles");
        if (!tileCache.exists()) {
            tileCache.mkdirs();
        }
        Configuration.getInstance().setOsmdroidTileCache(tileCache);
    }

    private MapView createMapView() {
        MapView createdMapView = new MapView(this);
        createdMapView.setTileSource(TileSourceFactory.MAPNIK);
        createdMapView.setUseDataConnection(false);
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
        addressInput.setHint("住所をオフライン検索");
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
                performOfflineSearch();
                return true;
            }
            return false;
        });

        Button searchButton = new Button(this);
        searchButton.setText("検索");
        searchButton.setOnClickListener(view -> performOfflineSearch());

        LinearLayout downloadRow = new LinearLayout(this);
        downloadRow.setOrientation(LinearLayout.HORIZONTAL);
        downloadRow.setPadding(0, dp(8), 0, 0);

        mapDownloadButton = new Button(this);
        addressDownloadButton = new Button(this);

        LinearLayout.LayoutParams firstButtonParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        firstButtonParams.rightMargin = dp(8);
        mapDownloadButton.setLayoutParams(firstButtonParams);
        mapDownloadButton.setOnClickListener(view -> startMapDownload());

        LinearLayout.LayoutParams secondButtonParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        addressDownloadButton.setLayoutParams(secondButtonParams);
        addressDownloadButton.setOnClickListener(view -> startAddressDownload());

        downloadRow.addView(mapDownloadButton);
        downloadRow.addView(addressDownloadButton);

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(12f);
        statusView.setPadding(0, dp(8), 0, 0);

        inputRow.addView(addressInput);
        inputRow.addView(searchButton);
        container.addView(inputRow);
        container.addView(downloadRow);
        container.addView(statusView);
        return container;
    }

    private void performOfflineSearch() {
        if (addressInput == null) {
            return;
        }

        String query = addressInput.getText().toString().trim();
        if (query.isEmpty()) {
            updateSearchStatus("検索状態: 住所を入力してください。");
            return;
        }

        hideKeyboard();
        updateSearchStatus("検索状態: オフライン検索中...");

        new Thread(() -> {
            try {
                OfflineAddressGeocoder.SearchResult result = offlineAddressGeocoder.search(query);
                runOnUiThread(() -> applySearchResult(query, result));
            } catch (IOException e) {
                runOnUiThread(() -> updateSearchStatus("検索状態: 住所辞書の読み込みに失敗しました。"));
            }
        }).start();
    }

    private void applySearchResult(String query, OfflineAddressGeocoder.SearchResult result) {
        if (result == null) {
            if (searchOverlay != null) {
                searchOverlay.removeAllItems();
            }
            if (mapView != null) {
                mapView.invalidate();
            }
            updateSearchStatus("検索状態: 該当する住所が見つかりませんでした。");
            return;
        }
        if (mapView == null || searchOverlay == null) {
            updateSearchStatus("検索状態: 地図の初期化が完了していません。");
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

        updateSearchStatus(String.format(
                Locale.getDefault(),
                "検索結果: %s\n入力: %s\n緯度: %.6f  経度: %.6f",
                result.getDisplayName(),
                query,
                result.getLatitude(),
                result.getLongitude()));
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

    private void updateSearchStatus(String text) {
        searchStatusMessage = text;
        refreshStatusView();
    }

    private void updateMapStatusText(String text) {
        mapStatusMessage = text;
        refreshStatusView();
    }

    private void updateAddressStatusText(String text) {
        addressStatusMessage = text;
        refreshStatusView();
    }

    private void updateGpsStatusText(String text) {
        gpsStatusMessage = text;
        refreshStatusView();
    }

    private void refreshStatusView() {
        if (statusView == null) {
            return;
        }

        StringBuilder statusBuilder = new StringBuilder();
        appendStatus(statusBuilder, mapStatusMessage);
        appendStatus(statusBuilder, addressStatusMessage);
        appendStatus(statusBuilder, searchStatusMessage);
        appendStatus(statusBuilder, gpsStatusMessage);
        statusView.setText(statusBuilder.toString());
    }

    private void appendStatus(StringBuilder statusBuilder, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (statusBuilder.length() > 0) {
            statusBuilder.append('\n');
        }
        statusBuilder.append(text);
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

        updateGpsStatusText(fromCache ? "GPS状態: キャッシュ位置を表示中" : "GPS状態: 現在地を取得しました");
        updateSensorInfoDisplay();
        if (mapView != null) {
            mapView.invalidate();
        }
    }

    private void startGpsAcquisitionFlow() {
        if (locationManager == null) {
            updateGpsStatusText("GPS状態: LocationManager取得失敗");
            return;
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
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
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                updateGpsStatusText("GPS状態: GPSプロバイダが無効です");
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
            updateGpsStatusText("GPS状態: 更新停止に失敗しました");
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
        disposeMapsForgeTileSource();
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
            updateGpsStatusText("GPS状態: 権限許可済み、測位開始");
            subscribeGpsUpdates();
        } else {
            updateGpsStatusText("GPS状態: 権限が拒否されました");
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void refreshMapProvider() {
        if (mapView == null) {
            return;
        }

        disposeMapsForgeTileSource();

        File mapFile = offlineDataRepository.getMapFile();
        if (!mapFile.isFile() || mapFile.length() <= 0L) {
            applyFallbackTileProvider();
            updateMapStatusText("地図状態: 日本地図未取得。地図DL/更新で最新データを取得してください。");
            return;
        }

        try {
            MapsForgeTileSource.createInstance(getApplication());
            MapsForgeTileSource newTileSource = MapsForgeTileSource.createFromFiles(
                    new File[]{mapFile},
                    null,
                    "japan-offline",
                    "ja");
            mapView.setTileProvider(new MapsForgeTileProvider(new SimpleRegisterReceiver(this), newTileSource, null));
            mapView.setUseDataConnection(false);
            mapView.setScrollableAreaLimitDouble(newTileSource.getBoundsOsmdroid());
            mapsForgeTileSource = newTileSource;

            if (lastGpsLocation == null && lastSearchLocation == null) {
                mapView.getController().setZoom(6);
                mapView.getController().setCenter(DEFAULT_START_POINT);
            }
            mapView.invalidate();
            updateMapStatusText("地図状態: 日本地図をオフライン表示中 (" + formatFileSize(mapFile.length()) + ")");
        } catch (RuntimeException e) {
            applyFallbackTileProvider();
            updateMapStatusText("地図状態: 日本地図の読み込みに失敗しました。地図DL/更新で再取得してください。");
        }
    }

    private void applyFallbackTileProvider() {
        MapTileProviderBasic basicTileProvider = new MapTileProviderBasic(getApplicationContext(), TileSourceFactory.MAPNIK);
        mapView.setTileProvider(basicTileProvider);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setUseDataConnection(false);
        mapView.setScrollableAreaLimitDouble(null);
        mapView.invalidate();
    }

    private void disposeMapsForgeTileSource() {
        if (mapsForgeTileSource != null) {
            mapsForgeTileSource.dispose();
            mapsForgeTileSource = null;
        }
    }

    private void refreshOfflineDataStatus() {
        if (!mapDownloadInProgress) {
            File mapFile = offlineDataRepository.getMapFile();
            if (mapFile.isFile() && mapFile.length() > 0L) {
                updateMapStatusText("地図状態: 日本地図をオフライン表示中 (" + formatFileSize(mapFile.length()) + ")");
            } else {
                updateMapStatusText("地図状態: 日本地図未取得。地図DL/更新で最新データを取得してください。");
            }
        }

        if (!addressDownloadInProgress) {
            File addressDatabaseFile = offlineDataRepository.getAddressDatabaseFile();
            if (addressDatabaseFile.isFile() && addressDatabaseFile.length() > 0L) {
                updateAddressStatusText("住所状態: ダウンロード済み住所DBを検索に使用します (" + formatFileSize(addressDatabaseFile.length()) + ")");
            } else {
                updateAddressStatusText("住所状態: 同梱辞書で検索中。住所DL/更新で日本住所DBを取得できます。");
            }
        }

        updateDownloadButtons();
    }

    private void updateDownloadButtons() {
        boolean busy = mapDownloadInProgress || addressDownloadInProgress;

        if (mapDownloadButton != null) {
            mapDownloadButton.setEnabled(!busy);
            mapDownloadButton.setText(mapDownloadInProgress
                    ? "地図DL中..."
                    : (offlineDataRepository.hasMapFile() ? "地図再取得" : "地図DL"));
        }

        if (addressDownloadButton != null) {
            addressDownloadButton.setEnabled(!busy);
            addressDownloadButton.setText(addressDownloadInProgress
                    ? "住所DL中..."
                    : (offlineDataRepository.hasAddressDatabase() ? "住所再取得" : "住所DL"));
        }
    }

    private void startMapDownload() {
        if (mapDownloadInProgress || addressDownloadInProgress) {
            return;
        }

        mapDownloadInProgress = true;
        updateDownloadButtons();
        updateMapStatusText("地図状態: 日本地図のダウンロードを開始しています...");

        new Thread(() -> {
            try {
                File downloadedMapFile = offlineDataRepository.downloadMap(new OfflineDataRepository.ProgressListener() {
                    @Override
                    public void onStart(long totalBytes) {
                        runOnUiThread(() -> updateMapStatusText(buildDownloadStatus("地図状態: 日本地図DL中", 0L, totalBytes)));
                    }

                    @Override
                    public void onProgress(long downloadedBytes, long totalBytes) {
                        runOnUiThread(() -> updateMapStatusText(buildDownloadStatus("地図状態: 日本地図DL中", downloadedBytes, totalBytes)));
                    }
                });

                runOnUiThread(() -> {
                    refreshMapProvider();
                    updateMapStatusText("地図状態: 日本地図を更新しました (" + formatFileSize(downloadedMapFile.length()) + ")");
                });
            } catch (IOException e) {
                runOnUiThread(() -> updateMapStatusText("地図状態: ダウンロード失敗: " + readableErrorMessage(e)));
            } finally {
                mapDownloadInProgress = false;
                runOnUiThread(this::refreshOfflineDataStatus);
            }
        }).start();
    }

    private void startAddressDownload() {
        if (mapDownloadInProgress || addressDownloadInProgress) {
            return;
        }

        addressDownloadInProgress = true;
        updateDownloadButtons();
        updateAddressStatusText("住所状態: 日本住所DBのダウンロードを開始しています...");

        new Thread(() -> {
            try {
                File downloadedAddressFile = offlineDataRepository.downloadAddressDatabase(new OfflineDataRepository.ProgressListener() {
                    @Override
                    public void onStart(long totalBytes) {
                        runOnUiThread(() -> updateAddressStatusText(buildDownloadStatus("住所状態: 日本住所DB DL中", 0L, totalBytes)));
                    }

                    @Override
                    public void onProgress(long downloadedBytes, long totalBytes) {
                        runOnUiThread(() -> updateAddressStatusText(buildDownloadStatus("住所状態: 日本住所DB DL中", downloadedBytes, totalBytes)));
                    }
                });

                runOnUiThread(() -> updateAddressStatusText("住所状態: DB取得完了。検索インデックスを作成中です..."));
                offlineAddressGeocoder.prepareDownloadedDatabase();

                runOnUiThread(() -> {
                    updateAddressStatusText("住所状態: ダウンロード済み住所DBを検索に使用します (" + formatFileSize(downloadedAddressFile.length()) + ")");
                    updateSearchStatus("検索状態: 最新の住所DBでオフライン検索できます。");
                });
            } catch (IOException e) {
                runOnUiThread(() -> updateAddressStatusText("住所状態: ダウンロード失敗: " + readableErrorMessage(e)));
            } finally {
                addressDownloadInProgress = false;
                runOnUiThread(this::refreshOfflineDataStatus);
            }
        }).start();
    }

    private String buildDownloadStatus(String prefix, long downloadedBytes, long totalBytes) {
        if (totalBytes > 0L) {
            return String.format(
                    Locale.getDefault(),
                    "%s %s / %s",
                    prefix,
                    formatFileSize(downloadedBytes),
                    formatFileSize(totalBytes));
        }
        return String.format(
                Locale.getDefault(),
                "%s %s",
                prefix,
                formatFileSize(downloadedBytes));
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0L) {
            return "0 B";
        }

        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unitIndex = 0;
        while (value >= 1024d && unitIndex < units.length - 1) {
            value /= 1024d;
            unitIndex++;
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex]);
    }

    private String readableErrorMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "不明なエラー";
        }
        return message;
    }
}
