package com.dji.sdk.sample.demo.camera;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

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

import dji.common.battery.BatteryState;
import dji.common.camera.SettingsDefinitions;
import dji.common.camera.SystemState;
import dji.common.error.DJIError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.gimbal.Rotation;
import dji.common.gimbal.RotationMode;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.battery.Battery;
import dji.sdk.camera.Camera;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.gimbal.Gimbal;
import dji.sdk.products.Aircraft;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import dji.common.flightcontroller.FlightMode;

public class ObjectDetectionView extends FrameLayout implements PresentableView {

    private TextView tvBattery, tvAltitude, tvSpeed, tvGps, tvUploadStatus;
    private Button btnPhoto, btnRecord, btnGimbalUp, btnGimbalDown;
    private MapView mapView;
    private Marker droneMarker;
    private boolean isMapExpanded = false;

    private boolean isRecording = false;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // Firebase
    private FirebaseFirestore db;

    // Deduplication
    private double lastUploadLat = 0;
    private double lastUploadLng = 0;
    private long lastUploadTime = 0;
    private static final long UPLOAD_COOLDOWN_MS = 10000; // 10 seconds
    private static final double MIN_DISTANCE_METERS = 3.0; // 3 meters

    // Current drone location
    private double currentLat = 0;
    private double currentLng = 0;
    private double currentAlt = 0;

    public ObjectDetectionView(Context context) {
        super(context);
        init(context);
    }

    public ObjectDetectionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        // OSMDroid config
        Configuration.getInstance().setUserAgentValue(context.getPackageName());

        LayoutInflater.from(context).inflate(R.layout.view_object_detection, this, true);

        tvBattery      = findViewById(R.id.tv_battery);
        tvAltitude     = findViewById(R.id.tv_altitude);
        tvSpeed        = findViewById(R.id.tv_speed);
        tvGps          = findViewById(R.id.tv_gps);
        tvUploadStatus = findViewById(R.id.tv_upload_status);
        btnPhoto       = findViewById(R.id.btn_photo);
        btnRecord      = findViewById(R.id.btn_record);
        btnGimbalUp    = findViewById(R.id.btn_gimbal_up);
        btnGimbalDown  = findViewById(R.id.btn_gimbal_down);

        // Map setup
        mapView = findViewById(R.id.map_view);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(18.0);

        // Tap map to expand/shrink
        FrameLayout mapContainer = findViewById(R.id.map_container);
        mapContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleMapSize(mapContainer);
            }
        });

        // Drone marker
        droneMarker = new Marker(mapView);
        droneMarker.setTitle("Drone");
        droneMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        mapView.getOverlays().add(droneMarker);

        // Firebase
        try {
            db = FirebaseFirestore.getInstance();
        } catch (Exception e) {
            db = null;
        }

        setupButtons();
        setupDetectionCallback();
    }

    private void toggleMapSize(FrameLayout mapContainer) {
        android.view.ViewGroup.LayoutParams params = mapContainer.getLayoutParams();
        if (!isMapExpanded) {
            params.width = (int) (320 * getResources().getDisplayMetrics().density);
            params.height = (int) (280 * getResources().getDisplayMetrics().density);
            isMapExpanded = true;
            mapView.getController().setZoom(18.0);
        } else {
            params.width = (int) (160 * getResources().getDisplayMetrics().density);
            params.height = (int) (120 * getResources().getDisplayMetrics().density);
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
                    if (!detections.isEmpty()) {
                        tryUploadToFirebase(detections.size());
                    }
                }
            });
        }
    }

    private void tryUploadToFirebase(int detectionCount) {
        // Validate location
        if (Double.isNaN(currentLat) || Double.isNaN(currentLng)) return;
        if (currentLat == 0 && currentLng == 0) return;
        if (currentAlt == 0 && currentLat == 0) return;

        long now = System.currentTimeMillis();

        // Check cooldown
        if (now - lastUploadTime < UPLOAD_COOLDOWN_MS) return;

        // Check distance moved
        if (lastUploadTime > 0) {
            double distance = calculateDistance(
                    lastUploadLat, lastUploadLng,
                    currentLat, currentLng);
            if (distance < MIN_DISTANCE_METERS) return;
        }

        // All checks passed — upload
        lastUploadTime = now;
        lastUploadLat  = currentLat;
        lastUploadLng  = currentLng;

        uploadToFirebase(detectionCount, currentLat, currentLng, currentAlt);
    }

    private void uploadToFirebase(int count, double lat, double lng, double alt) {
        if (db == null) return;

        Map<String, Object> data = new HashMap<>();
        data.put("jumlahDeteksi", count);
        data.put("labels", "rubbish");
        data.put("latitude", lat);
        data.put("longitude", lng);
        data.put("altitude", alt);
        data.put("timestamp", new SimpleDateFormat(
                "MMMM dd, yyyy 'at' hh:mm:ss a z",
                Locale.US).format(new Date()));

        db.collection("waste_detections")
                .add(data)
                .addOnSuccessListener(ref -> {
                    mainHandler.post(() ->
                            tvUploadStatus.setText("Firebase: ✓ Uploaded"));
                })
                .addOnFailureListener(e -> {
                    mainHandler.post(() ->
                            tvUploadStatus.setText("Firebase: ✗ Failed"));
                });
    }

    // Haversine formula — distance in meters between two GPS points
    private double calculateDistance(double lat1, double lng1,
                                     double lat2, double lng2) {
        final int R = 6371000; // Earth radius in meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private void updateMapMarker(double lat, double lng) {
        GeoPoint point = new GeoPoint(lat, lng);
        droneMarker.setPosition(point);
        mapView.getController().setCenter(point);
        mapView.invalidate();
    }

    private void setupButtons() {

        btnPhoto.setOnClickListener(v -> {
            BaseProduct product = DJISampleApplication.getProductInstance();
            if (!(product instanceof Aircraft)) { toast("No aircraft connected"); return; }
            Camera camera = ((Aircraft) product).getCamera();
            if (camera == null) { toast("Camera unavailable"); return; }
            camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO,
                    error -> {
                        if (error != null) { toast("Mode error: " + error.getDescription()); return; }
                        camera.startShootPhoto(error2 ->
                                toast(error2 == null ? "Photo taken!" :
                                        "Photo failed: " + error2.getDescription()));
                    });
        });

        btnRecord.setOnClickListener(v -> {
            BaseProduct product = DJISampleApplication.getProductInstance();
            if (!(product instanceof Aircraft)) { toast("No aircraft connected"); return; }
            Camera camera = ((Aircraft) product).getCamera();
            if (camera == null) { toast("Camera unavailable"); return; }
            if (!isRecording) {
                // Check current mode first — don't switch if already correct
                camera.getMode(new CommonCallbacks.CompletionCallbackWith<SettingsDefinitions.CameraMode>() {
                    @Override
                    public void onSuccess(SettingsDefinitions.CameraMode currentMode) {
                        if (currentMode == SettingsDefinitions.CameraMode.RECORD_VIDEO) {
                            // Already in correct mode — start directly
                            camera.startRecordVideo(error ->
                                    toast(error == null ? "Recording started!" :
                                            "Record failed: " + error.getDescription()));
                        } else {
                            camera.setMode(SettingsDefinitions.CameraMode.RECORD_VIDEO, error -> {
                                if (error != null) { toast("Mode error: " + error.getDescription()); return; }
                                camera.startRecordVideo(error2 ->
                                        toast(error2 == null ? "Recording started!" :
                                                "Record failed: " + error2.getDescription()));
                            });
                        }
                    }
                    @Override
                    public void onFailure(DJIError error) {
                        toast("Cannot check camera mode: " + error.getDescription());
                    }
                });
            } else {
                camera.stopRecordVideo(error ->
                        toast(error == null ? "Recording saved!" :
                                "Stop failed: " + error.getDescription()));
            }
        });

        btnGimbalUp.setOnClickListener(v -> rotateGimbal(30f));
        btnGimbalDown.setOnClickListener(v -> rotateGimbal(-30f));
    }

    private void rotateGimbal(float pitch) {
        BaseProduct product = DJISampleApplication.getProductInstance();
        if (!(product instanceof Aircraft)) { toast("No aircraft connected"); return; }
        Gimbal gimbal = ((Aircraft) product).getGimbal();
        if (gimbal == null) { toast("Gimbal unavailable"); return; }
        Rotation rotation = new Rotation.Builder()
                .mode(RotationMode.RELATIVE_ANGLE)
                .pitch(pitch)
                .yaw(Rotation.NO_ROTATION)
                .roll(Rotation.NO_ROTATION)
                .time(1)
                .build();
        gimbal.rotate(rotation, error -> {
            if (error != null) toast("Gimbal error: " + error.getDescription());
        });
    }

    private void startTelemetry() {
        BaseProduct product = DJISampleApplication.getProductInstance();
        if (!(product instanceof Aircraft)) return;
        Aircraft aircraft = (Aircraft) product;

        FlightController fc = aircraft.getFlightController();
        if (fc != null) {
            fc.setStateCallback(new FlightControllerState.Callback() {
                @Override
                public void onUpdate(@NonNull FlightControllerState state) {
                    final double lat = state.getAircraftLocation().getLatitude();
                    final double lng = state.getAircraftLocation().getLongitude();
                    final float alt  = (float) state.getAircraftLocation().getAltitude();
                    final float speedX = state.getVelocityX();
                    final float speedY = state.getVelocityY();
                    final float speed  = (float) Math.sqrt(speedX * speedX + speedY * speedY);
                    final int   sats   = state.getSatelliteCount();

                    // Only update if valid GPS fix
                    if (!Double.isNaN(lat) && !Double.isNaN(lng) && lat != 0 && lng != 0) {
                        currentLat = lat;
                        currentLng = lng;
                        currentAlt = alt;
                    }

                    mainHandler.post(() -> {
                        tvAltitude.setText(String.format("ALT: %.1fm", alt));
                        tvSpeed.setText(String.format("SPD: %.1fm/s", speed));
                        tvGps.setText("GPS: " + sats + " sats");

                        // Update map
                        if (lat != 0 && lng != 0) {
                            updateMapMarker(lat, lng);
                        }
                    });
                }
            });
        }

        Battery battery = aircraft.getBattery();
        if (battery != null) {
            battery.setStateCallback(new BatteryState.Callback() {
                @Override
                public void onUpdate(@NonNull BatteryState state) {
                    final int pct = state.getChargeRemainingInPercent();
                    mainHandler.post(() -> {
                        tvBattery.setText("BAT: " + pct + "%");
                        tvBattery.setTextColor(pct > 20 ?
                                android.graphics.Color.WHITE :
                                android.graphics.Color.RED);
                    });
                }
            });
        }

        Camera camera = aircraft.getCamera();
        if (camera != null) {
            camera.setSystemStateCallback(new SystemState.Callback() {
                @Override
                public void onUpdate(@NonNull SystemState state) {
                    final boolean recording = state.isRecording();
                    if (recording != isRecording) {
                        isRecording = recording;
                        mainHandler.post(() ->
                                btnRecord.setText(isRecording ? "Stop Record" : "Start Record"));
                    }
                }
            });
        }
    }

    private void stopTelemetry() {
        BaseProduct product = DJISampleApplication.getProductInstance();
        if (!(product instanceof Aircraft)) return;
        Aircraft aircraft = (Aircraft) product;

        FlightController fc = aircraft.getFlightController();
        if (fc != null) fc.setStateCallback(null);

        Battery battery = aircraft.getBattery();
        if (battery != null) battery.setStateCallback(null);

        Camera camera = aircraft.getCamera();
        if (camera != null) camera.setSystemStateCallback(null);
    }

    // ---- AUTO PREFLIGHT CHECK ----
    private void runPreflightChecks() {
        // Small delay to let SDK initialize first
        mainHandler.postDelayed(() -> {
            StringBuilder warnings = new StringBuilder();
            java.util.List<Runnable> actions = new java.util.ArrayList<>();

            // Check 1 — Location permission
            if (ContextCompat.checkSelfPermission(getContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                warnings.append("❌ Location permission not granted\n\n");
                actions.add(() -> {
                    if (getContext() instanceof android.app.Activity) {
                        androidx.core.app.ActivityCompat.requestPermissions(
                                (android.app.Activity) getContext(),
                                new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                1001);
                    }
                });
            }

            // Check 2 — GPS enabled on phone
            LocationManager lm = (LocationManager) getContext()
                    .getSystemService(Context.LOCATION_SERVICE);
            if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                warnings.append("❌ Phone GPS is OFF\n\n");
                actions.add(() -> {
                    getContext().startActivity(
                            new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                });
            }

            // Check 3 — Drone connected
            BaseProduct product = DJISampleApplication.getProductInstance();
            if (!(product instanceof Aircraft)) {
                warnings.append("❌ No drone connected\n\n");
                showPreflightDialog(warnings.toString(), null);
                return;
            }

            Aircraft aircraft = (Aircraft) product;
            FlightController fc = aircraft.getFlightController();

            if (fc == null) {
                warnings.append("❌ Flight controller unavailable\n\n");
                showPreflightDialog(warnings.toString(), null);
                return;
            }

            // Check 4 — Flight Controller state (GPS sats, IMU, etc.)
            fc.setStateCallback(new FlightControllerState.Callback() {
                private boolean checked = false;

                @Override
                public void onUpdate(@NonNull FlightControllerState state) {
                    if (checked) return;
                    checked = true;

                    // Check satellite count
                    int sats = state.getSatelliteCount();
                    if (sats < 6) {
                        warnings.append("⚠️ Weak GPS signal (")
                                .append(sats)
                                .append(" satellites, need 6+)\n")
                                .append("   → Wait for more satellites\n\n");
                    }

                    // Check flight mode — not safe to fly if in certain modes
                    FlightMode mode = state.getFlightMode();
                    if (mode == FlightMode.MANUAL || mode == FlightMode.ATTI) {
                        warnings.append("⚠️ Drone is in manual/attitude mode\n")
                                .append("   → Switch to GPS mode for safe flight\n\n");
                    }

                    // Check if drone is flying already
                    if (state.isFlying()) {
                        warnings.append("ℹ️ Drone is already airborne\n\n");
                    }

                    // Check battery
                    Battery battery = aircraft.getBattery();
                    if (battery != null) {
                        battery.setStateCallback(new BatteryState.Callback() {
                            private boolean batteryChecked = false;

                            @Override
                            public void onUpdate(@NonNull BatteryState batteryState) {
                                if (batteryChecked) return;
                                batteryChecked = true;

                                int pct = batteryState.getChargeRemainingInPercent();
                                if (pct < 25) {
                                    warnings.append("❌ Battery critically low (")
                                            .append(pct)
                                            .append("%)\n")
                                            .append("   → Charge before flying\n\n");
                                } else if (pct < 50) {
                                    warnings.append("⚠️ Battery low (")
                                            .append(pct)
                                            .append("%)\n")
                                            .append("   → Consider charging\n\n");
                                }

                                // Show final result
                                showPreflightDialog(warnings.toString(),
                                        actions.isEmpty() ? null : actions.get(0));

                                // Reset battery callback after check
                                battery.setStateCallback(null);
                            }
                        });
                    } else {
                        showPreflightDialog(warnings.toString(),
                                actions.isEmpty() ? null : actions.get(0));
                    }
                }
            });

        }, 2000); // 2 second delay after view opens
    }

    private void showPreflightDialog(String warnings, Runnable fixAction) {
        mainHandler.post(() -> {
            if (warnings.trim().isEmpty()) {
                // All good — show brief toast
                Toast.makeText(getContext(),
                        "✅ All preflight checks passed!",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            android.app.AlertDialog.Builder builder =
                    new android.app.AlertDialog.Builder(getContext())
                            .setTitle("⚠️ Preflight Warnings")
                            .setMessage(warnings.trim())
                            .setCancelable(true);

            if (fixAction != null) {
                builder.setPositiveButton("Fix Now", (dialog, which) -> fixAction.run());
            }

            builder.setNegativeButton("Dismiss", null);
            builder.show();
        });
    }
// ---- END PREFLIGHT CHECK ----

    private void toast(final String msg) {
        mainHandler.post(() ->
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        DJISampleApplication.getEventBus()
                .post(new MainActivity.RequestStartFullScreenEvent());
        if (getContext() instanceof android.app.Activity) {
            ((android.app.Activity) getContext()).getWindow().setFlags(
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
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
            ((android.app.Activity) getContext()).getWindow().clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        super.onDetachedFromWindow();
    }

    @Override
    public int getDescription() {
        return R.string.object_detection_title;
    }

    @NonNull
    @Override
    public String getHint() {
        return this.getClass().getSimpleName() + ".java";
    }
}