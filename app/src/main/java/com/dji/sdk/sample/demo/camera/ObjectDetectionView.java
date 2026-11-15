package com.dji.sdk.sample.demo.camera;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.controller.DJISampleApplication;
import com.dji.sdk.sample.internal.controller.MainActivity;
import com.dji.sdk.sample.internal.view.BaseFpvView;
import com.dji.sdk.sample.internal.view.PresentableView;
import com.dji.sdk.sample.internal.view.YoloDetector;
import com.google.firebase.firestore.FirebaseFirestore;

// --- GOOGLE MAPS IMPORTS ---
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dji.common.flightcontroller.FlightControllerState;
import dji.sdk.base.BaseProduct;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;

public class ObjectDetectionView extends FrameLayout implements PresentableView, OnMapReadyCallback {

    private TextView tvUploadStatus;
    private MapView mapView;
    private GoogleMap gMap;
    private Marker droneMarker;
    private boolean isMapExpanded = false;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // Firebase
    private FirebaseFirestore db;

    // Geo-grid peak aggregation (U10, R10): dedup source of truth is the
    // ~11 m grid cell, not the trackId (which churn made unreliable).
    private final com.dji.sdk.sample.internal.util.GeoGridAggregator gridAggregator =
            new com.dji.sdk.sample.internal.util.GeoGridAggregator();

    // GPS state
    private volatile double currentLat = 0, currentLng = 0, currentAlt = 0;
    // --- Skema Pengujian Deteksi (field test data collection) ---
    private boolean isTestSessionActive = false;
    private String testScenario = "";
    private int testGroundTruthCount = 0;
    private String testLightCondition = "";
    private double testAltitudeM = 0;
    private final java.util.List<Map<String, Object>> testRawDetections = new java.util.ArrayList<>();
    public ObjectDetectionView(Context context) {
        super(context);
        init(context);
    }

    private final Map<String, Integer> testCellPeaks = new HashMap<>();
    private volatile int currentSatelliteCount = 0;
    private static final int MIN_SATELLITES = 6; // sesuai field-test-protocol
    private long lastGpsWarnMs = 0;
    private long lastMapCameraUpdateMs = 0;
    private static final long MAP_CAMERA_UPDATE_INTERVAL_MS = 500;

    public ObjectDetectionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_object_detection, this, true);

        tvUploadStatus = findViewById(R.id.tv_upload_status);

        tvUploadStatus.setOnClickListener(v -> {
            if (isTestSessionActive) {
                endTestSession();
            } else {
                startTestSession();
            }
        });

        // Map setup
        mapView = findViewById(R.id.map_view);
        if (mapView != null) {
            mapView.onCreate(null);
            mapView.getMapAsync(this);
        }

        FrameLayout mapContainer = findViewById(R.id.map_container);
        mapContainer.setOnClickListener(v -> toggleMapSize(mapContainer));

        findViewById(R.id.btn_back).setOnClickListener(v -> {
            if (getContext() instanceof android.app.Activity) {
                ((android.app.Activity) getContext()).onBackPressed();
            }
        });

        // Firebase — persistensi offline sudah aktif default di Android (dok. resmi),
        // jadi deteksi saat sinyal hilang antri lokal dan sinkron otomatis (R10).
        try {
            db = FirebaseFirestore.getInstance();
        } catch (Exception e) {
            db = null;
        }

        setupDetectionCallback();
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        gMap = googleMap;
        gMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
        gMap.getUiSettings().setZoomControlsEnabled(false);
        gMap.getUiSettings().setMapToolbarEnabled(false);

        LatLng startPoint = new LatLng(-6.2, 106.8);
        droneMarker = gMap.addMarker(new MarkerOptions()
                .position(startPoint)
                .title("Drone"));

        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startPoint, 16.0f));
    }

    private void toggleMapSize(FrameLayout mapContainer) {
        android.view.ViewGroup.LayoutParams params = mapContainer.getLayoutParams();
        if (!isMapExpanded) {
            params.width = (int)(320 * getResources().getDisplayMetrics().density);
            params.height = (int)(280 * getResources().getDisplayMetrics().density);
            isMapExpanded = true;
            if (gMap != null) gMap.animateCamera(CameraUpdateFactory.zoomTo(18.0f));
        } else {
            params.width = (int)(160 * getResources().getDisplayMetrics().density);
            params.height = (int)(120 * getResources().getDisplayMetrics().density);
            isMapExpanded = false;
            if (gMap != null) gMap.animateCamera(CameraUpdateFactory.zoomTo(16.0f));
        }
        mapContainer.setLayoutParams(params);
        mapContainer.requestLayout();
    }

    private void setupDetectionCallback() {
        BaseFpvView baseFpvView = findViewById(R.id.base_fpv_view);
        if (baseFpvView != null) {
            baseFpvView.setDetectionCallback(detections -> {
                if (!detections.isEmpty()) tryUploadToFirebase(detections);
            });
        }
    }

    private void tryUploadToFirebase(List<YoloDetector.Detection> detections) {
        if (Double.isNaN(currentLat) || Double.isNaN(currentLng)) return;
        if (currentLat == 0 && currentLng == 0) return;

        // GPS quality gate: di bawah MIN_SATELLITES posisi tidak andal dan akan
        // mencemari geo-grid. Tampilkan di layar (tidak ada Logcat di lapangan).
        if (currentSatelliteCount < MIN_SATELLITES) {
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastGpsWarnMs > 3000) {
                lastGpsWarnMs = nowMs;
                final int sats = currentSatelliteCount;
                mainHandler.post(() -> tvUploadStatus.setText(
                        "GPS lemah (" + sats + " sat) — upload ditahan"));
            }
            return;
        }

        // Count qualifying detections in THIS cycle (simultaneous count),
        // then let the geo-grid decide whether the cell's peak increased.
        int count = 0;
        float bestConfidence = 0f;
        String label = "rubbish";
        for (YoloDetector.Detection d : detections) {
            if (d.trackId <= 0) continue;
            if (d.confidence < com.dji.sdk.sample.internal.util
                    .DetectionConfig.UPLOAD_CONFIDENCE_THRESHOLD) continue;
            count++;
            if (isTestSessionActive) {
                Map<String, Object> raw = new HashMap<>();
                raw.put("confidence", (double) d.confidence);
                raw.put("timestamp", System.currentTimeMillis());
                raw.put("trackId", d.trackId);
                testRawDetections.add(raw);
            }
            if (d.confidence > bestConfidence) {
                bestConfidence = d.confidence;
                if (d.label != null) label = d.label;
            }
        }
        if (count == 0) return;

        int newPeak = gridAggregator.recordCount(currentLat, currentLng, count);
        if (isTestSessionActive) {
            String testKey = gridAggregator.cellKey(currentLat, currentLng);
            Integer prevTestPeak = testCellPeaks.get(testKey);
            if (prevTestPeak == null || count > prevTestPeak) {
                testCellPeaks.put(testKey, count);
            }
        }
        if (newPeak > 0) {
            uploadAggregatedCell(currentLat, currentLng, currentAlt,
                    newPeak, bestConfidence, label);
        }
    }

    /**
     * One aggregated Firestore document per geo-grid cell (doc id = cell
     * key), updated by merge only when the cell's peak count increases.
     * Fields are ADDITIVE over the old schema so existing dashboard readers
     * keep working: jumlahDeteksi now carries the peak count, and the
     * timestamp string format is unchanged (Calendar Boundary parsing).
     */
    private void uploadAggregatedCell(double lat, double lng, double alt,
                                      int peakCount, float confidence, String label) {
        if (db == null) return;

        final String cellKey = gridAggregator.cellKey(lat, lng);
        Map<String, Object> data = new HashMap<>();
        data.put("label", label);
        data.put("confidence", Math.round(confidence * 100));
        data.put("jumlahDeteksi", peakCount);           // dashboard-compatible
        data.put("peakCount", peakCount);               // additive, explicit
        data.put("cellKey", cellKey);                   // additive
        data.put("latitude", lat);
        data.put("longitude", lng);
        data.put("altitude", alt);
        data.put("timestamp", new SimpleDateFormat("MMMM dd, yyyy 'at' hh:mm:ss a z", Locale.US).format(new Date()));
        data.put("timestampEpochMs", System.currentTimeMillis()); // additive

        db.collection("waste_detections_v2").document(cellKey)
                .set(data, com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener(ref -> mainHandler.post(() ->
                        tvUploadStatus.setText("Sel " + cellKey + " · " + peakCount + " Terekam")))
                .addOnFailureListener(e -> mainHandler.post(() ->
                        tvUploadStatus.setText("Antri Offline / Gagal Upload")));
    }

    public void clearAllTracks() {
        mainHandler.post(() -> {
            gridAggregator.reset();
            // Complete the reset: clear detector tracks + ID counter too (R3)
            BaseFpvView baseFpvView = findViewById(R.id.base_fpv_view);
            if (baseFpvView != null) baseFpvView.resetTracker();
            tvUploadStatus.setText("Memori Tracker Direset");
        });
    }

    private void startTestSession() {
        Context ctx = getContext();
        android.widget.LinearLayout layout = new android.widget.LinearLayout(ctx);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        final android.widget.EditText inputSkenario = new android.widget.EditText(ctx);
        inputSkenario.setHint("Skenario (contoh: 1, 2, 3, 4)");
        layout.addView(inputSkenario);

        final android.widget.EditText inputGroundTruth = new android.widget.EditText(ctx);
        inputGroundTruth.setHint("Jumlah sampah riil (ground truth)");
        inputGroundTruth.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(inputGroundTruth);

        final android.widget.EditText inputCahaya = new android.widget.EditText(ctx);
        inputCahaya.setHint("Kondisi cahaya (contoh: terang, mendung)");
        layout.addView(inputCahaya);

        final android.widget.EditText inputKetinggian = new android.widget.EditText(ctx);
        inputKetinggian.setHint("Ketinggian terbang (meter)");
        inputKetinggian.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(inputKetinggian);

        new android.app.AlertDialog.Builder(ctx)
                .setTitle("Mulai Sesi Pengujian")
                .setView(layout)
                .setPositiveButton("Mulai", (d, w) -> {
                    testScenario = inputSkenario.getText().toString();
                    testLightCondition = inputCahaya.getText().toString();
                    try {
                        testGroundTruthCount = Integer.parseInt(inputGroundTruth.getText().toString());
                    } catch (NumberFormatException e) {
                        testGroundTruthCount = 0;
                    }
                    try {
                        testAltitudeM = Double.parseDouble(inputKetinggian.getText().toString());
                    } catch (NumberFormatException e) {
                        testAltitudeM = 0;
                    }
                    gridAggregator.reset();
                    testRawDetections.clear();
                    testCellPeaks.clear();
                    isTestSessionActive = true;
                    tvUploadStatus.setText("Sesi Uji AKTIF: " + testScenario);
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void endTestSession() {
        isTestSessionActive = false;

        if (db == null) {
            tvUploadStatus.setText("Sesi Uji selesai (Firebase tidak tersedia)");
            return;
        }

        int detectedCountSum = 0;
        for (int peak : testCellPeaks.values()) {
            detectedCountSum += peak;
        }
        final int detectedCount = detectedCountSum;
        String cellKey = gridAggregator.cellKey(currentLat, currentLng);

        float confidenceSum = 0f;
        for (Map<String, Object> d : testRawDetections) {
            confidenceSum += ((Number) d.get("confidence")).floatValue();
        }
        float avgConfidence = testRawDetections.isEmpty() ? 0f : confidenceSum / testRawDetections.size();

        Map<String, Object> data = new HashMap<>();
        data.put("skenario", testScenario);
        data.put("ground_truth_count", testGroundTruthCount);
        data.put("kondisi_cahaya", testLightCondition);
        data.put("ketinggian_m", testAltitudeM);
        data.put("detected_count", detectedCount);
        data.put("avg_confidence", avgConfidence);
        data.put("cellKey", cellKey);
        data.put("latitude", currentLat);
        data.put("longitude", currentLng);
        data.put("timestamp", new SimpleDateFormat("MMMM dd, yyyy 'at' hh:mm:ss a z", Locale.US).format(new Date()));
        data.put("raw_detections", new java.util.ArrayList<>(testRawDetections));

        db.collection("pengujian_deteksi").add(data)
                .addOnSuccessListener(ref -> mainHandler.post(() ->
                        tvUploadStatus.setText("Sesi Uji Tersimpan: " + detectedCount + " terdeteksi")))
                .addOnFailureListener(e -> mainHandler.post(() -> {
                    tvUploadStatus.setText("Gagal Simpan Sesi Uji");
                    new android.app.AlertDialog.Builder(getContext())
                            .setTitle("Gagal Simpan Sesi Uji")
                            .setMessage(String.valueOf(e))
                            .setPositiveButton("OK", null)
                            .show();
                }));
    }



    private void startTelemetry() {
        BaseProduct product = DJISampleApplication.getProductInstance();
        if (!(product instanceof Aircraft)) return;
        FlightController fc = ((Aircraft) product).getFlightController();
        if (fc == null) return;
        fc.setStateCallback(state -> {
            double lat = state.getAircraftLocation().getLatitude();
            double lng = state.getAircraftLocation().getLongitude();
            float alt  = (float) state.getAircraftLocation().getAltitude();
            currentSatelliteCount = state.getSatelliteCount();
            if (!Double.isNaN(lat) && !Double.isNaN(lng) && lat != 0 && lng != 0) {
                currentLat = lat; currentLng = lng; currentAlt = alt;
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastMapCameraUpdateMs >= MAP_CAMERA_UPDATE_INTERVAL_MS) {
                    lastMapCameraUpdateMs = nowMs;
                    mainHandler.post(() -> {
                        if (gMap != null && droneMarker != null) {
                            LatLng point = new LatLng(lat, lng);
                            droneMarker.setPosition(point);
                            gMap.animateCamera(CameraUpdateFactory.newLatLng(point));
                        }
                    });
                }
            }
        });
    }

    private void stopTelemetry() {
        BaseProduct product = DJISampleApplication.getProductInstance();
        if (!(product instanceof Aircraft)) return;
        FlightController fc = ((Aircraft) product).getFlightController();
        if (fc != null) fc.setStateCallback(null);
    }

    private void runPreflightChecks() {
        mainHandler.postDelayed(() -> {
            StringBuilder warnings = new StringBuilder();
            java.util.List<Runnable> actions = new java.util.ArrayList<>();

            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                warnings.append("❌ Location permission not granted\n\n");
                actions.add(() -> {
                    if (getContext() instanceof android.app.Activity) {
                        androidx.core.app.ActivityCompat.requestPermissions((android.app.Activity) getContext(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1001);
                    }
                });
            }

            LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
            if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                warnings.append("❌ Phone GPS is OFF\n\n");
                actions.add(() -> getContext().startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
            }

            if (!warnings.toString().isEmpty()) {
                mainHandler.post(() -> {
                    android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(getContext())
                            .setTitle("⚠️ Preflight Warnings")
                            .setMessage(warnings.toString().trim())
                            .setCancelable(true);
                    if (!actions.isEmpty()) {
                        builder.setPositiveButton("Fix Now", (d, w) -> actions.get(0).run());
                    }
                    builder.setNegativeButton("Dismiss", null).show();
                });
            }
        }, 2000);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        DJISampleApplication.getEventBus().post(new MainActivity.RequestStartFullScreenEvent());
        if (getContext() instanceof android.app.Activity) {
            android.app.Activity a = (android.app.Activity) getContext();
            a.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN, android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (mapView != null) mapView.onResume();
        startTelemetry();
        setupDetectionCallback();
        runPreflightChecks();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopTelemetry();
        if (mapView != null) {
            mapView.onPause();
            mapView.onDestroy();
        }
        DJISampleApplication.getEventBus().post(new MainActivity.RequestEndFullScreenEvent());
        if (getContext() instanceof android.app.Activity) {
            android.app.Activity a = (android.app.Activity) getContext();
            a.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            a.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        super.onDetachedFromWindow();
    }

    @Override
    public int getDescription() { return R.string.object_detection_title; }

    @NonNull
    @Override
    public String getHint() { return this.getClass().getSimpleName() + ".java"; }
}