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
import com.dji.sdk.sample.internal.view.PresentableView;

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

public class ObjectDetectionView extends FrameLayout implements PresentableView {

    private TextView tvBattery;
    private TextView tvAltitude;
    private TextView tvSpeed;
    private TextView tvGps;
    private Button btnTakeoff;
    private Button btnLand;
    private Button btnPhoto;
    private Button btnRecord;
    private Button btnGimbalUp;
    private Button btnGimbalDown;

    private boolean isRecording = false;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

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

        tvBattery    = findViewById(R.id.tv_battery);
        tvAltitude   = findViewById(R.id.tv_altitude);
        tvSpeed      = findViewById(R.id.tv_speed);
        tvGps        = findViewById(R.id.tv_gps);
        btnTakeoff   = findViewById(R.id.btn_takeoff);
        btnLand      = findViewById(R.id.btn_land);
        btnPhoto     = findViewById(R.id.btn_photo);
        btnRecord    = findViewById(R.id.btn_record);
        btnGimbalUp   = findViewById(R.id.btn_gimbal_up);
        btnGimbalDown = findViewById(R.id.btn_gimbal_down);

        setupButtons();
    }

    private void setupButtons() {

        // ---- TAKEOFF ----
        btnTakeoff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BaseProduct product = DJISampleApplication.getProductInstance();
                if (!(product instanceof Aircraft)) { toast("No aircraft connected"); return; }
                FlightController fc = ((Aircraft) product).getFlightController();
                if (fc == null) { toast("Flight controller unavailable"); return; }
                fc.startTakeoff(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {
                        toast(error == null ? "Taking off..." :
                                "Takeoff failed: " + error.getDescription());
                    }
                });
            }
        });

        // ---- LAND ----
        btnLand.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BaseProduct product = DJISampleApplication.getProductInstance();
                if (!(product instanceof Aircraft)) { toast("No aircraft connected"); return; }
                FlightController fc = ((Aircraft) product).getFlightController();
                if (fc == null) { toast("Flight controller unavailable"); return; }
                fc.startLanding(new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError error) {
                        toast(error == null ? "Landing..." :
                                "Landing failed: " + error.getDescription());
                    }
                });
            }
        });

        // ---- PHOTO ----
        btnPhoto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BaseProduct product = DJISampleApplication.getProductInstance();
                if (!(product instanceof Aircraft)) { toast("No aircraft connected"); return; }
                Camera camera = ((Aircraft) product).getCamera();
                if (camera == null) { toast("Camera unavailable"); return; }
                camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO,
                        new CommonCallbacks.CompletionCallback() {
                            @Override
                            public void onResult(DJIError error) {
                                if (error != null) { toast("Mode error: " + error.getDescription()); return; }
                                camera.startShootPhoto(new CommonCallbacks.CompletionCallback() {
                                    @Override
                                    public void onResult(DJIError error) {
                                        toast(error == null ? "Photo taken!" :
                                                "Photo failed: " + error.getDescription());
                                    }
                                });
                            }
                        });
            }
        });

        // ---- RECORD ----
        btnRecord.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BaseProduct product = DJISampleApplication.getProductInstance();
                if (!(product instanceof Aircraft)) { toast("No aircraft connected"); return; }
                Camera camera = ((Aircraft) product).getCamera();
                if (camera == null) { toast("Camera unavailable"); return; }
                if (!isRecording) {
                    camera.setMode(SettingsDefinitions.CameraMode.RECORD_VIDEO,
                            new CommonCallbacks.CompletionCallback() {
                                @Override
                                public void onResult(DJIError error) {
                                    if (error != null) { toast("Mode error: " + error.getDescription()); return; }
                                    camera.startRecordVideo(new CommonCallbacks.CompletionCallback() {
                                        @Override
                                        public void onResult(DJIError error) {
                                            toast(error == null ? "Recording started!" :
                                                    "Record failed: " + error.getDescription());
                                        }
                                    });
                                }
                            });
                } else {
                    camera.stopRecordVideo(new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onResult(DJIError error) {
                            toast(error == null ? "Recording saved!" :
                                    "Stop failed: " + error.getDescription());
                        }
                    });
                }
            }
        });

        // ---- GIMBAL UP ----
        btnGimbalUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rotateGimbal(30f); // tilt up 30 degrees
            }
        });

        // ---- GIMBAL DOWN ----
        btnGimbalDown.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rotateGimbal(-30f); // tilt down 30 degrees
            }
        });
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

        gimbal.rotate(rotation, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError error) {
                if (error != null) {
                    toast("Gimbal error: " + error.getDescription());
                }
            }
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
                    final float altitude = (float) state.getAircraftLocation().getAltitude();
                    final float speedX   = state.getVelocityX();
                    final float speedY   = state.getVelocityY();
                    final float speed    = (float) Math.sqrt(speedX * speedX + speedY * speedY);
                    final int   satCount = state.getSatelliteCount();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvAltitude.setText(String.format("ALT: %.1fm", altitude));
                            tvSpeed.setText(String.format("SPD: %.1fm/s", speed));
                            tvGps.setText("GPS: " + satCount + " sats");
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
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            tvBattery.setText("BAT: " + pct + "%");
                            tvBattery.setTextColor(pct > 20 ?
                                    android.graphics.Color.WHITE :
                                    android.graphics.Color.RED);
                        }
                    });
                }
            });
        }

        // Sync record state with RC controller
        Camera camera = aircraft.getCamera();
        if (camera != null) {
            camera.setSystemStateCallback(new SystemState.Callback() {
                @Override
                public void onUpdate(@NonNull SystemState state) {
                    final boolean recording = state.isRecording();
                    if (recording != isRecording) {
                        isRecording = recording;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                btnRecord.setText(isRecording ? "Stop Record" : "Start Record");
                            }
                        });
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

    private void toast(final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        DJISampleApplication.getEventBus()
                .post(new MainActivity.RequestStartFullScreenEvent());
        if (getContext() instanceof android.app.Activity) {
            android.app.Activity activity = (android.app.Activity) getContext();
            activity.getWindow().setFlags(
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        startTelemetry();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopTelemetry();
        DJISampleApplication.getEventBus()
                .post(new MainActivity.RequestEndFullScreenEvent());
        if (getContext() instanceof android.app.Activity) {
            android.app.Activity activity = (android.app.Activity) getContext();
            activity.getWindow().clearFlags(
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