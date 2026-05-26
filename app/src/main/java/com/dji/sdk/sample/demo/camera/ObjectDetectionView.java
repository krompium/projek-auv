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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.controller.DJISampleApplication;
import com.dji.sdk.sample.internal.controller.MainActivity;
import com.dji.sdk.sample.internal.view.BaseFpvView;
import com.dji.sdk.sample.internal.view.PresentableView;
import com.dji.sdk.sample.internal.view.YoloDetector;
import com.google.firebase.firestore.FirebaseFirestore;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

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

public class ObjectDetectionView extends FrameLayout implements PresentableView {

    private TextView tvUploadStatus;
    private MapView mapView;
    private Marker droneMarker;
    private boolean isMapExpanded = false;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // Firebase
    private FirebaseFirestore db;
    private double lastUploadLat = 0, lastUploadLng = 0;
    private long lastUploadTime = 0;
    private static final long UPLOAD_COOLDOWN_MS = 5000;
    private static final double MIN_DISTANCE_METERS = 2.0;

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
        Configuration.getInstance().setUserAgentValue(context.getPackageName());
        LayoutInflater.from(context).inflate(R.layout.view_object_detection, this, true);

        tvUploadStatus = findViewById(R.id.tv_upload_status);

        // Map setup
        mapView = findViewById(R.id.map_view);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(18.0);

        FrameLayout mapContainer = findViewById(R.id.map_container);
        mapContainer.setOnClickListener(v -> toggleMapSize(mapContainer));

        droneMarker = new Marker(mapView);
        droneMarker.setTitle("Drone");
        droneMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        mapView.getOverlays().add(droneMarker);

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

    private void toggleMapSize(FrameLayout mapContainer) {
        android.view.ViewGroup.LayoutParams params = mapContainer.getLayoutParams();
        if (!isMapExpanded) {
            params.width = (int)(320 * getResources().getDisplayMetrics().density);
            params.height = (int)(280 * getResources().getDisplayMetrics().density);
            isMapExpanded = true;
            mapView.getController().setZoom(18.0);
        } else {
            params.width = (int)(160 * getResources().getDisplayMetrics().density);
            params.height = (int)(120 * getResources().getDisplayMetrics().density);
            isMapExpanded = false;
            mapView.getController().setZoom(16.0);
        }
        mapContainer.setLayoutParams(params);
        mapContainer.requestLayout();
        mapView.invalidate();
    }

    private void setupDetectionCallback() {
        BaseFpvView baseFpvView = findViewById(R.id.base_fpv_view);
        if (baseFpvView != null) {
            baseFpvView.setDetectionCallback(new BaseFpvView.DetectionCallback() {
                @Override
                public void onDetectionResult(List<YoloDetector.Detection> detections) {
                    if (!detections.isEmpty()) tryUploadToFirebase(detections);
                }
            });
        }
    }

    private void tryUploadToFirebase(List<YoloDetector.Detection> detections) {
        if (Double.isNaN(currentLat) || Double.isNaN(currentLng)) return;
        if (currentLat == 0 && currentLng == 0) return;
        long now = System.currentTimeMillis();
        if (now - lastUploadTime < UPLOAD_COOLDOWN_MS) return;
        if (lastUploadTime > 0) {
            if (calculateDistance(lastUploadLat, lastUploadLng,
                    currentLat, currentLng) < MIN_DISTANCE_METERS) return;
        }
        lastUploadTime = now;
        lastUploadLat = currentLat;
        lastUploadLng = currentLng;
        for (YoloDetector.Detection d : detections) {
            uploadToFirebase(d, currentLat, currentLng, currentAlt);
        }
    }

    private void uploadToFirebase(YoloDetector.Detection detection,
                                  double lat, double lng, double alt) {
        if (db == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("label", detection.label);
        data.put("confidence", Math.round(detection.confidence * 100));
        data.put("latitude", lat);
        data.put("longitude", lng);
        data.put("altitude", alt);
        data.put("timestamp", new SimpleDateFormat(
                "MMMM dd, yyyy 'at' hh:mm:ss a z", Locale.US).format(new Date()));
        db.collection("waste_detections_v2").add(data)
                .addOnSuccessListener(ref -> mainHandler.post(() ->
                        tvUploadStatus.setText("✓ " + detection.label)))
                .addOnFailureListener(e -> mainHandler.post(() ->
                        tvUploadStatus.setText("✗ Failed")));
    }

    private double calculateDistance(double lat1, double lng1,
                                     double lat2, double lng2) {
        final int R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                *Math.sin(dLng/2)*Math.sin(dLng/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    private void startTelemetry() {
        BaseProduct product = DJISampleApplication.getProductInstance();
        if (!(product instanceof Aircraft)) return;
        FlightController fc = ((Aircraft) product).getFlightController();
        if (fc == null) return;
        fc.setStateCallback(new FlightControllerState.Callback() {
            @Override
            public void onUpdate(@NonNull FlightControllerState state) {
                double lat = state.getAircraftLocation().getLatitude();
                double lng = state.getAircraftLocation().getLongitude();
                float alt  = (float) state.getAircraftLocation().getAltitude();
                currentSatelliteCount = state.getSatelliteCount();
                if (!Double.isNaN(lat) && !Double.isNaN(lng) && lat != 0 && lng != 0) {
                    currentLat = lat; currentLng = lng; currentAlt = alt;
                    mainHandler.post(() -> {
                        GeoPoint point = new GeoPoint(lat, lng);
                        droneMarker.setPosition(point);
                        mapView.getController().setCenter(point);
                        mapView.invalidate();
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
        // PreFlightStatusWidget handles warnings automatically
        // We only check phone-side conditions here
        mainHandler.postDelayed(() -> {
            StringBuilder warnings = new StringBuilder();
            java.util.List<Runnable> actions = new java.util.ArrayList<>();

            if (ContextCompat.checkSelfPermission(getContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                warnings.append("❌ Location permission not granted\n\n");
                actions.add(() -> {
                    if (getContext() instanceof android.app.Activity) {
                        androidx.core.app.ActivityCompat.requestPermissions(
                                (android.app.Activity) getContext(),
                                new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1001);
                    }
                });
            }

            LocationManager lm = (LocationManager) getContext()
                    .getSystemService(Context.LOCATION_SERVICE);
            if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                warnings.append("❌ Phone GPS is OFF\n\n");
                actions.add(() -> getContext().startActivity(
                        new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
            }

            if (!warnings.toString().isEmpty()) {
                mainHandler.post(() -> {
                    android.app.AlertDialog.Builder builder =
                            new android.app.AlertDialog.Builder(getContext())
                                    .setTitle("⚠️ Preflight Warnings")
                                    .setMessage(warnings.toString().trim())
                                    .setCancelable(true);
                    if (!actions.isEmpty()) {
                        builder.setPositiveButton("Fix Now",
                                (d, w) -> actions.get(0).run());
                    }
                    builder.setNegativeButton("Dismiss", null).show();
                });
            }
        }, 2000);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        DJISampleApplication.getEventBus()
                .post(new MainActivity.RequestStartFullScreenEvent());
        if (getContext() instanceof android.app.Activity) {
            android.app.Activity a = (android.app.Activity) getContext();
            a.getWindow().setFlags(
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            a.getWindow().addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        mapView.onResume();
        startTelemetry();
        setupDetectionCallback();
        runPreflightChecks();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopTelemetry();
        mapView.onPause();
        DJISampleApplication.getEventBus()
                .post(new MainActivity.RequestEndFullScreenEvent());
        if (getContext() instanceof android.app.Activity) {
            android.app.Activity a = (android.app.Activity) getContext();
            a.getWindow().clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
            a.getWindow().clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        super.onDetachedFromWindow();
    }

    @Override
    public int getDescription() { return R.string.object_detection_title; }

    @NonNull
    @Override
    public String getHint() { return this.getClass().getSimpleName() + ".java"; }
}