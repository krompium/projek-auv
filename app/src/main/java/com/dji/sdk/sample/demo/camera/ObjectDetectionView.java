package com.dji.sdk.sample.demo.camera;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Bundle;
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
import java.util.HashSet;
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

    // --- DEEPSORT MEMORY TRACKER ---
    private HashSet<Integer> uploadedTrackIds = new HashSet<>();

    // GPS state
    private double currentLat = 0, currentLng = 0, currentAlt = 0;
    private int currentSatelliteCount = 0;

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

        // Firebase
        try { db = FirebaseFirestore.getInstance(); }
        catch (Exception e) { db = null; }

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

        for (YoloDetector.Detection d : detections) {
            if (d.trackId <= 0) continue;

            if (d.confidence < 0.40f) continue;

            if (!uploadedTrackIds.contains(d.trackId)) {
                uploadedTrackIds.add(d.trackId);
                uploadToFirebase(d, currentLat, currentLng, currentAlt);
            }
        }
    }

    private void uploadToFirebase(YoloDetector.Detection detection, double lat, double lng, double alt) {
        if (db == null) return;

        Map<String, Object> data = new HashMap<>();

        data.put("label", detection.label != null ? detection.label : "rubbish");
        data.put("confidence", Math.round(detection.confidence * 100));
        data.put("trackId", detection.trackId);
        data.put("jumlahDeteksi", 1);
        data.put("latitude", lat);
        data.put("longitude", lng);
        data.put("altitude", alt);
        data.put("timestamp", new SimpleDateFormat("MMMM dd, yyyy 'at' hh:mm:ss a z", Locale.US).format(new Date()));

        db.collection("waste_detections_v2").add(data)
                .addOnSuccessListener(ref -> mainHandler.post(() ->
                        tvUploadStatus.setText("Sampah #" + detection.trackId + " Terekam")))
                .addOnFailureListener(e -> mainHandler.post(() ->
                        tvUploadStatus.setText("Gagal Upload")));
    }

    public void clearAllTracks() {
        mainHandler.post(() -> {
            uploadedTrackIds.clear();
            // Complete the reset: clear detector tracks + ID counter too (R3)
            BaseFpvView baseFpvView = findViewById(R.id.base_fpv_view);
            if (baseFpvView != null) baseFpvView.resetTracker();
            tvUploadStatus.setText("Memori Tracker Direset");
        });
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
                mainHandler.post(() -> {
                    if (gMap != null && droneMarker != null) {
                        LatLng point = new LatLng(lat, lng);
                        droneMarker.setPosition(point);
                        gMap.animateCamera(CameraUpdateFactory.newLatLng(point));
                    }
                });
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
