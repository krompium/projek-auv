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
import com.dji.sdk.sample.internal.util.DetectionConfig;
import com.dji.sdk.sample.internal.util.GeoGridAggregator;
import com.dji.sdk.sample.internal.view.BaseFpvView;
import com.dji.sdk.sample.internal.view.PresentableView;
import com.dji.sdk.sample.internal.view.YoloDetector;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

// --- GOOGLE MAPS IMPORTS ---
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dji.sdk.base.BaseProduct;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;

public class ObjectDetectionView extends FrameLayout implements PresentableView, OnMapReadyCallback {

    private TextView tvUploadStatus;
    private BaseFpvView baseFpvView;
    private MapView mapView;
    private GoogleMap gMap;
    private Marker droneMarker;
    private boolean isMapExpanded = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Firebase
    private FirebaseFirestore db;

    // Geo-grid peak + Track-Set Union aggregation with 6m Haversine boundary hysteresis (U10, R10)
    private final GeoGridAggregator gridAggregator = new GeoGridAggregator();

    // GPS & Flight kinematics state
    private volatile double currentLat = 0, currentLng = 0, currentAlt = 0;
    private volatile float currentSpeedMs = 0f;
    private volatile int currentSatelliteCount = 0;
    private static final int MIN_SATELLITES = 6; // sesuai field-test-protocol
    private long lastGpsWarnMs = 0;
    private long lastMapCameraUpdateMs = 0;
    private static final long MAP_CAMERA_UPDATE_INTERVAL_MS = 500;

    // --- Skema Pengujian Deteksi (field test data collection) ---
    private volatile boolean isTestSessionActive = false;
    private String testScenario = "";
    private int testGroundTruthCount = 0;
    private String testLightCondition = "";
    private double testAltitudeM = 0;
    private long testStartTimeMs = 0;
    private int testSimultaneousPeak = 0;
    private final Map<Integer, Float> testUniqueTrackPeakConf = new HashMap<>();
    private final Map<String, Integer> testCellPeaks = new HashMap<>();
    private final List<Map<String, Object>> testRawDetections = new ArrayList<>();
    private double testSumAlt = 0.0;
    private double testSumSpeed = 0.0;
    private float testMaxSpeedMs = 0f;
    private long testSumCaptureMs = 0;
    private long testSumInferMs = 0;
    private int testCycleCount = 0;
    private int testStartDroppedFrames = 0;

    public ObjectDetectionView(Context context) {
        super(context);
        init(context);
    }

    public ObjectDetectionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_object_detection, this, true);

        tvUploadStatus = findViewById(R.id.tv_upload_status);
        baseFpvView = findViewById(R.id.base_fpv_view);

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
        if (baseFpvView == null) {
            baseFpvView = findViewById(R.id.base_fpv_view);
        }
        if (baseFpvView != null) {
            baseFpvView.setDetectionCallback(this::tryUploadToFirebase);
        }
    }

    private void tryUploadToFirebase(List<YoloDetector.Detection> detections) {
        int n = (detections != null) ? detections.size() : 0;
        if (n == 0 && !isTestSessionActive) return;

        int[] confirmedIds = (n > 0) ? new int[n] : null;
        float[] confirmedConfs = (n > 0) ? new float[n] : null;
        int confirmedCount = 0;
        float confSum = 0f;
        float bestConfidence = 0f;
        String label = "rubbish";

        for (int i = 0; i < n; i++) {
            YoloDetector.Detection d = detections.get(i);
            // SORT Gate: hanya objek terkonfirmasi (hits >= MIN_TRACK_HITS) yang dihitung,
            // sehingga 100% sinkron dengan kotak yang digambar di OverlayView.
            if (!d.isConfirmed()) continue;
//            if (d.confidence < DetectionConfig.UPLOAD_CONFIDENCE_THRESHOLD) continue;

            confirmedIds[confirmedCount] = d.trackId;
            confirmedConfs[confirmedCount] = d.confidence;
            confirmedCount++;
            confSum += d.confidence;
            if (d.confidence > bestConfidence) {
                bestConfidence = d.confidence;
                if (d.label != null) label = d.label;
            }
        }

        final float meanConfPct = (confirmedCount > 0) ? (confSum / confirmedCount) * 100f : 0f;

        // Rekam telemetri sesi uji lapangan pada setiap siklus (termasuk saat confirmedCount == 0)
        if (isTestSessionActive) {
            testCycleCount++;
            testSumAlt += currentAlt;
            testSumSpeed += currentSpeedMs;
            if (currentSpeedMs > testMaxSpeedMs) testMaxSpeedMs = currentSpeedMs;

            final long capMs = (baseFpvView != null) ? baseFpvView.getLastCaptureMs() : 0;
            final long infMs = (baseFpvView != null) ? baseFpvView.getLastInferMs() : 0;
            final int drops = (baseFpvView != null) ? baseFpvView.getDroppedFrameCount() : 0;
            testSumCaptureMs += capMs;
            testSumInferMs += infMs;

            if (confirmedCount > 0) {
                if (confirmedCount > testSimultaneousPeak) {
                    testSimultaneousPeak = confirmedCount;
                }
                for (int i = 0; i < confirmedCount; i++) {
                    int tid = confirmedIds[i];
                    float c = confirmedConfs[i];
                    Float prevBest = testUniqueTrackPeakConf.get(tid);
                    if (prevBest == null || c > prevBest) {
                        testUniqueTrackPeakConf.put(tid, c);
                        // Simpan 1 record bersih per trackId unik (maks 200 entri)
                        if (prevBest == null && testRawDetections.size() < 200) {
                            Map<String, Object> raw = new HashMap<>();
                            raw.put("confidence", (double) c);
                            raw.put("timestamp", System.currentTimeMillis());
                            raw.put("trackId", tid);
                            raw.put("lat", currentLat);
                            raw.put("lng", currentLng);
                            raw.put("alt", currentAlt);
                            raw.put("speedMs", (double) currentSpeedMs);
                            testRawDetections.add(raw);
                        }
                    }
                }
                if (!Double.isNaN(currentLat) && !Double.isNaN(currentLng)
                        && (currentLat != 0 || currentLng != 0)) {
                    String testKey = gridAggregator.cellKey(currentLat, currentLng);
                    Integer prevTestPeak = testCellPeaks.get(testKey);
                    if (prevTestPeak == null || confirmedCount > prevTestPeak) {
                        testCellPeaks.put(testKey, confirmedCount);
                    }
                }
            }

            // Tulis ke field_test_telemetry.csv di background thread (0% disk I/O di UI thread)
            final long tsNow = System.currentTimeMillis();
            final String scenSnap = testScenario;
            final int rawCountSnap = n;
            final int confCountSnap = confirmedCount;
            final int uniqueTotalSnap = testUniqueTrackPeakConf.size();
            final double latSnap = currentLat;
            final double lngSnap = currentLng;
            final float altSnap = (float) currentAlt;
            final float spdSnap = currentSpeedMs;
            final int satSnap = currentSatelliteCount;
            if (baseFpvView != null) {
                baseFpvView.postIoTask(() -> {
                    if (baseFpvView.getMetricsLogger() != null) {
                        baseFpvView.getMetricsLogger().logFieldTelemetry(
                                tsNow, scenSnap, capMs, infMs, drops,
                                rawCountSnap, confCountSnap, confCountSnap,
                                uniqueTotalSnap, meanConfPct,
                                latSnap, lngSnap, altSnap, spdSnap, satSnap);
                    }
                });
            }

            final int uniqueNow = testUniqueTrackPeakConf.size();
            final int bestNow = Math.max(testSimultaneousPeak, uniqueNow);
            mainHandler.post(() -> tvUploadStatus.setText(String.format(Locale.US,
                    "UJI [%s]: %d/%d (Peak:%d ID:%d | %.1fm/s)",
                    testScenario, bestNow, testGroundTruthCount,
                    testSimultaneousPeak, uniqueNow, currentSpeedMs)));
        }

        if (confirmedCount == 0) return;
        if (Double.isNaN(currentLat) || Double.isNaN(currentLng)) return;
        if (currentLat == 0 && currentLng == 0) return;

        // GPS quality gate: di bawah MIN_SATELLITES posisi tidak andal dan akan
        // mencemari geo-grid. Tampilkan di layar (tidak ada Logcat di lapangan).
        if (currentSatelliteCount < MIN_SATELLITES) {
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastGpsWarnMs > 3000) {
                lastGpsWarnMs = nowMs;
                final int sats = currentSatelliteCount;
                if (!isTestSessionActive) {
                    mainHandler.post(() -> tvUploadStatus.setText(
                            "GPS lemah (" + sats + " sat) — upload ditahan"));
                }
            }
            return;
        }

        // Agregasi geo-grid (~11 m) + Haversine Proximity (<= 6 m) + Track-Set Union
        GeoGridAggregator.CellSummary summary = gridAggregator.recordConfirmedDetections(
                currentLat, currentLng, confirmedIds, confirmedConfs, confirmedCount);
        if (summary != null) {
            uploadAggregatedCell(summary.cellKey, currentLat, currentLng, currentAlt,
                    summary.effectiveCount, summary.simultaneousPeak,
                    summary.uniqueTrackCount, bestConfidence, label);
        }
    }

    /**
     * One aggregated Firestore document per geo-grid cell (doc id = anchored cellKey),
     * updated by merge only when the cell's effective count increases.
     * Preserves all 8 core fields expected by the web dashboard.
     */
    private void uploadAggregatedCell(String cellKey, double lat, double lng, double alt,
                                      int effectiveCount, int simPeak, int uniqueTracks,
                                      float confidence, String label) {
        if (db == null) return;

        Map<String, Object> data = new HashMap<>();
        data.put("label", label);
        data.put("confidence", Math.round(confidence * 100));
        data.put("jumlahDeteksi", effectiveCount);      // dashboard-compatible
        data.put("peakCount", effectiveCount);          // additive, explicit
        data.put("simultaneousPeak", simPeak);          // additive
        data.put("uniqueTrackCount", uniqueTracks);     // additive
        data.put("cellKey", cellKey);                   // additive
        data.put("latitude", lat);
        data.put("longitude", lng);
        data.put("altitude", alt);
        data.put("timestamp", new SimpleDateFormat("MMMM dd, yyyy 'at' hh:mm:ss a z", Locale.US).format(new Date()));
        data.put("timestampEpochMs", System.currentTimeMillis()); // additive

        db.collection("waste_detections_v2").document(cellKey)
                .set(data, SetOptions.merge())
                .addOnSuccessListener(ref -> {
                    if (!isTestSessionActive) {
                        mainHandler.post(() -> tvUploadStatus.setText(
                                "Sel " + cellKey + " · " + effectiveCount + " Terekam"));
                    }
                })
                .addOnFailureListener(e -> {
                    if (!isTestSessionActive) {
                        mainHandler.post(() -> tvUploadStatus.setText(
                                "Antri Offline / Gagal Upload"));
                    }
                });
    }

    public void clearAllTracks() {
        mainHandler.post(() -> {
            gridAggregator.reset();
            testUniqueTrackPeakConf.clear();
            testSimultaneousPeak = 0;
            if (baseFpvView == null) {
                baseFpvView = findViewById(R.id.base_fpv_view);
            }
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
        inputSkenario.setHint("Skenario (contoh: 1 - 5m Statis, 4 - Bergerak)");
        layout.addView(inputSkenario);

        final android.widget.EditText inputGroundTruth = new android.widget.EditText(ctx);
        inputGroundTruth.setHint("Jumlah sampah riil (ground truth)");
        inputGroundTruth.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(inputGroundTruth);

        final android.widget.EditText inputCahaya = new android.widget.EditText(ctx);
        inputCahaya.setHint("Kondisi cahaya & latar (contoh: Cerah/Paving)");
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
                    testScenario = inputSkenario.getText().toString().trim();
                    if (testScenario.isEmpty()) testScenario = "Skenario-Uji";
                    testLightCondition = inputCahaya.getText().toString().trim();
                    if (testLightCondition.isEmpty()) testLightCondition = "-";
                    try {
                        testGroundTruthCount = Integer.parseInt(
                                inputGroundTruth.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        testGroundTruthCount = 0;
                    }
                    try {
                        testAltitudeM = Double.parseDouble(
                                inputKetinggian.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        testAltitudeM = 0;
                    }

                    gridAggregator.reset();
                    testRawDetections.clear();
                    testCellPeaks.clear();
                    testUniqueTrackPeakConf.clear();
                    testSimultaneousPeak = 0;
                    testStartTimeMs = System.currentTimeMillis();
                    testSumAlt = 0.0;
                    testSumSpeed = 0.0;
                    testMaxSpeedMs = 0f;
                    testSumCaptureMs = 0;
                    testSumInferMs = 0;
                    testCycleCount = 0;

                    if (baseFpvView == null) {
                        baseFpvView = findViewById(R.id.base_fpv_view);
                    }
                    if (baseFpvView != null) {
                        baseFpvView.resetTracker();
                        testStartDroppedFrames = baseFpvView.getDroppedFrameCount();
                        final String scen = testScenario;
                        final int gt = testGroundTruthCount;
                        final double tAlt = testAltitudeM;
                        baseFpvView.postIoTask(() -> {
                            if (baseFpvView.getMetricsLogger() != null) {
                                baseFpvView.getMetricsLogger().logEvent("TEST_START",
                                        "scenario=" + scen + " gt=" + gt + " alt=" + tAlt);
                            }
                        });
                    } else {
                        testStartDroppedFrames = 0;
                    }

                    isTestSessionActive = true;
                    tvUploadStatus.setText("Sesi Uji AKTIF: " + testScenario + " (Tap untuk Selesai)");
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void endTestSession() {
        isTestSessionActive = false;
        float durationSec = Math.max(0.1f, (System.currentTimeMillis() - testStartTimeMs) / 1000f);

        // Gunakan jumlah objek unik terkonfirmasi atau peak simultan tertinggi
        // (bebas dari bug double-counting akibat pergeseran GPS di batas sel grid)
        final int uniqueConfirmedCount = testUniqueTrackPeakConf.size();
        final int detectedCount = Math.max(testSimultaneousPeak, uniqueConfirmedCount);
        final int diff = testGroundTruthCount - detectedCount;
        final float accuracyPct = (testGroundTruthCount > 0)
                ? (detectedCount * 100.0f / testGroundTruthCount) : 0f;

        float confSum = 0f;
        for (Float c : testUniqueTrackPeakConf.values()) {
            confSum += c;
        }
        final float avgConfidence = testUniqueTrackPeakConf.isEmpty()
                ? 0f : (confSum / testUniqueTrackPeakConf.size());
        final float avgConfidencePct = avgConfidence * 100f;

        final float avgMeasuredAlt = (testCycleCount > 0)
                ? (float) (testSumAlt / testCycleCount) : (float) currentAlt;
        final float avgSpeedMs = (testCycleCount > 0)
                ? (float) (testSumSpeed / testCycleCount) : 0f;
        final float maxSpeedSnap = testMaxSpeedMs;
        final float meanCaptureMs = (testCycleCount > 0)
                ? (float) testSumCaptureMs / testCycleCount : 0f;
        final float meanInferMs = (testCycleCount > 0)
                ? (float) testSumInferMs / testCycleCount : 0f;
        final int sessionDrops = (baseFpvView != null)
                ? Math.max(0, baseFpvView.getDroppedFrameCount() - testStartDroppedFrames) : 0;

        // 1. Selalu simpan ke CSV lokal (/files/metrics/field_test_summary.csv) di background thread
        if (baseFpvView != null) {
            final String scenSnap = testScenario;
            final String lightSnap = testLightCondition;
            final float targetAltSnap = (float) testAltitudeM;
            final int gtSnap = testGroundTruthCount;
            final int simPeakSnap = testSimultaneousPeak;
            baseFpvView.postIoTask(() -> {
                if (baseFpvView.getMetricsLogger() != null) {
                    baseFpvView.getMetricsLogger().logFieldTestSummary(
                            scenSnap, lightSnap, targetAltSnap, avgMeasuredAlt,
                            maxSpeedSnap, avgSpeedMs, gtSnap,
                            simPeakSnap, uniqueConfirmedCount, detectedCount,
                            diff, accuracyPct, avgConfidencePct,
                            meanCaptureMs, meanInferMs, sessionDrops, durationSec);
                    baseFpvView.getMetricsLogger().logEvent("TEST_END",
                            String.format(Locale.US,
                                    "scenario=%s det=%d/%d (%.1f%%) avgConf=%.1f%% inf=%.1fms",
                                    scenSnap, detectedCount, gtSnap,
                                    accuracyPct, avgConfidencePct, meanInferMs));
                }
            });
        }

        if (db == null) {
            tvUploadStatus.setText(String.format(Locale.US,
                    "Uji Selesai (CSV Tersimpan): %d/%d (%.1f%%)",
                    detectedCount, testGroundTruthCount, accuracyPct));
            return;
        }

        String cellKey = gridAggregator.cellKey(currentLat, currentLng);

        // 2. Simpan ke koleksi Firestore 'pengujian_deteksi' (mempertahankan seluruh field asli
        //    ditambah field metrik Laporan Akhir)
        Map<String, Object> data = new HashMap<>();
        data.put("skenario", testScenario);
        data.put("ground_truth_count", testGroundTruthCount);
        data.put("kondisi_cahaya", testLightCondition);
        data.put("ketinggian_m", testAltitudeM);
        data.put("ketinggian_aktual_m", Math.round(avgMeasuredAlt * 100.0) / 100.0);
        data.put("kecepatan_maks_ms", Math.round(maxSpeedSnap * 100.0) / 100.0);
        data.put("kecepatan_rata_ms", Math.round(avgSpeedMs * 100.0) / 100.0);
        data.put("detected_count", detectedCount);
        data.put("peak_simultaneous", testSimultaneousPeak);
        data.put("unique_confirmed_tracks", uniqueConfirmedCount);
        data.put("selisih", diff);
        data.put("akurasi_persen", Math.round(accuracyPct * 10.0) / 10.0);
        data.put("avg_confidence", avgConfidence);
        data.put("avg_confidence_persen", Math.round(avgConfidencePct * 10.0) / 10.0);
        data.put("mean_capture_ms", Math.round(meanCaptureMs * 10.0) / 10.0);
        data.put("mean_infer_ms", Math.round(meanInferMs * 10.0) / 10.0);
        data.put("dropped_frames", sessionDrops);
        data.put("durasi_detik", Math.round(durationSec));
        data.put("cellKey", cellKey);
        data.put("latitude", currentLat);
        data.put("longitude", currentLng);
        data.put("timestamp", new SimpleDateFormat("MMMM dd, yyyy 'at' hh:mm:ss a z", Locale.US).format(new Date()));
        data.put("raw_detections", new ArrayList<>(testRawDetections));

        db.collection("pengujian_deteksi").add(data)
                .addOnSuccessListener(ref -> mainHandler.post(() ->
                        tvUploadStatus.setText(String.format(Locale.US,
                                "Sesi Uji Tersimpan: %d/%d (%.1f%%)",
                                detectedCount, testGroundTruthCount, accuracyPct))))
                .addOnFailureListener(e -> mainHandler.post(() -> {
                    tvUploadStatus.setText("CSV Lokal Aman · Gagal Upload Cloud");
                    new android.app.AlertDialog.Builder(getContext())
                            .setTitle("Disimpan ke CSV Lokal (Cloud Gagal)")
                            .setMessage("Data sudah aman di field_test_summary.csv.\nDetail Cloud: " + e)
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
            float vx   = state.getVelocityX();
            float vy   = state.getVelocityY();
            currentSpeedMs = (float) Math.hypot(vx, vy);
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
            List<Runnable> actions = new ArrayList<>();

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