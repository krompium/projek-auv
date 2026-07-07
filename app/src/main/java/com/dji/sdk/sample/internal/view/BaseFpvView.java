package com.dji.sdk.sample.internal.view;

import android.app.Service;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.widget.FrameLayout;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.util.MetricsLogger;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import dji.midware.usb.P3.UsbAccessoryService;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;

public class BaseFpvView extends FrameLayout implements TextureView.SurfaceTextureListener {

    private VideoFeeder.VideoDataListener videoDataListener = null;
    private DJICodecManager codecManager = null;
    private TextureView mVideoSurface = null;

    private HandlerThread processingThread;
    private Handler processingHandler;
    /**
     * Frame gate. compareAndSet makes the capture decision atomic: the old
     * volatile check-then-set had a race window against the processing
     * thread's reset in finally{}, allowing two frames in flight.
     */
    private final AtomicBoolean isProcessingFrame = new AtomicBoolean(false);
    private volatile YoloDetector yoloDetector = null;
    private OverlayView overlayView;

    private int droppedFrameCount = 0;
    private long lastCaptureStartTime = 0;
    private static final long MIN_CAPTURE_INTERVAL_MS = 400; // ms between capture attempts
    private int capturedFrameCount = 0;
    private MetricsLogger metricsLogger;

    // Callback to notify parent view of detections
    public interface DetectionCallback {
        void onDetectionResult(List<YoloDetector.Detection> detections);
    }
    private DetectionCallback detectionCallback = null;

    public void setDetectionCallback(DetectionCallback callback) {
        this.detectionCallback = callback;
    }

    /**
     * Full tracker reset (tracks + ID counter). Called from
     * ObjectDetectionView.clearAllTracks() so "reset memori tracker" clears
     * detector state too, not just the uploaded-ID set.
     */
    public void resetTracker() {
        YoloDetector detector = yoloDetector;
        if (detector != null) detector.reset();
        if (metricsLogger != null) metricsLogger.logEvent("TRACKER", "reset requested");
    }

    public BaseFpvView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initUI();
    }

    private void initUI() {
        // File-based metrics (USB is occupied by the RC, Logcat is impractical)
        metricsLogger = new MetricsLogger(
                new File(getContext().getExternalFilesDir(null), "metrics"));
        metricsLogger.logEvent("LIFECYCLE", "BaseFpvView init");

        // Start background processing thread
        processingThread = new HandlerThread("YuvProcessingThread");
        processingThread.start();
        processingHandler = new Handler(processingThread.getLooper());

        // Load YOLO model in background thread
        processingHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    yoloDetector = new YoloDetector(getContext().getApplicationContext());
                    new Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            android.widget.Toast.makeText(getContext(),
                                    "YOLO loaded OK!",
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                    });
                    // Offline parity harness (U6): one-shot replay of bundled
                    // test images when the run_harness marker file exists.
                    DetectionHarness.runIfRequested(
                            getContext().getApplicationContext(), yoloDetector, metricsLogger);
                } catch (Throwable e) {
                    final String err = e.getClass().getSimpleName() + ": " + e.getMessage();
                    new Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            android.widget.Toast.makeText(getContext(),
                                    "YOLO FAILED: " + err,
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });

        // Inflate layout
        LayoutInflater layoutInflater = (LayoutInflater) getContext()
                .getSystemService(Service.LAYOUT_INFLATER_SERVICE);
        layoutInflater.inflate(R.layout.view_fpv_and_camera_display, this, true);

        // Setup TextureView
        mVideoSurface = findViewById(R.id.texture_video_previewer_surface);
        if (mVideoSurface != null) {
            mVideoSurface.setSurfaceTextureListener(this);

            videoDataListener = new VideoFeeder.VideoDataListener() {
                @Override
                public void onReceive(byte[] bytes, int size) {
                    if (codecManager != null) {
                        codecManager.sendDataToDecoder(
                                bytes, size,
                                UsbAccessoryService.VideoStreamSource.Camera.getIndex());
                    }
                }
            };
        }

        // Get overlay from XML
        overlayView = findViewById(R.id.detection_overlay);

        initSDKCallback();

        // Ensure transform runs after layout is complete
        post(new Runnable() {
            @Override
            public void run() {
                updateTextureViewTransform();
            }
        });

        setBackgroundColor(android.graphics.Color.BLACK);
    }

    private void initSDKCallback() {
        try {
            VideoFeeder.getInstance()
                    .getPrimaryVideoFeed()
                    .addVideoDataListener(videoDataListener);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (codecManager == null) {
            codecManager = new DJICodecManager(
                    getContext().getApplicationContext(),
                    surface, 1920, 1080,
                    UsbAccessoryService.VideoStreamSource.Camera);
        }
        post(new Runnable() {
            @Override
            public void run() {
                updateTextureViewTransform();
            }
        });
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        updateTextureViewTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (codecManager != null) {
            codecManager.cleanSurface();
            codecManager = null;
        }
        if (processingThread != null) {
            processingThread.quitSafely();
            processingThread = null;
        }
        if (yoloDetector != null) {
            yoloDetector.close();
            yoloDetector = null;
        }
        if (overlayView != null) {
            overlayView.updateDetections(new java.util.ArrayList<>());
            overlayView = null;
        }
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        if (yoloDetector == null) return;
        if (mVideoSurface == null) return;

        long now = System.currentTimeMillis();
        if (now - lastCaptureStartTime < MIN_CAPTURE_INTERVAL_MS) return;
        lastCaptureStartTime = now;

        // Atomic frame gate: exactly one frame in flight, others count as dropped.
        if (!isProcessingFrame.compareAndSet(false, true)) {
            droppedFrameCount++;
            return;
        }
        capturedFrameCount++;

        // Measure getBitmap time on UI thread.
        // Capture at the source 16:9 aspect (640x360) — YoloDetector letterboxes
        // to the square model input, so no squash distortion is introduced (R2).
        long captureStart = System.currentTimeMillis();
        final Bitmap frameBitmap = mVideoSurface.getBitmap(640, 360);
        final long captureMs = System.currentTimeMillis() - captureStart;

        processingHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (frameBitmap == null) {
                        new Handler(android.os.Looper.getMainLooper()).post(() ->
                                android.widget.Toast.makeText(getContext(),
                                        "Frame capture null", android.widget.Toast.LENGTH_SHORT).show());
                        return;
                    }

                    // Measure inference time
                    long inferStart = System.currentTimeMillis();
                    List<YoloDetector.Detection> detections = yoloDetector.detect(frameBitmap);
                    final long inferMs = System.currentTimeMillis() - inferStart;
                    frameBitmap.recycle();

                    // File-based per-frame metrics (replaces Toast spam)
                    final int activeTracks = yoloDetector.getActiveTrackCount();
                    final int totalTrackIds = yoloDetector.getTotalTrackIdCount();
                    if (metricsLogger != null) {
                        metricsLogger.logFrame(System.currentTimeMillis(), captureMs, inferMs,
                                droppedFrameCount, detections.size(), activeTracks, totalTrackIds,
                                yoloDetector.isGpuActive(), MIN_CAPTURE_INTERVAL_MS);
                    }
                    final String debugLine = String.format(Locale.US,
                            "cap %dms  inf %dms  drop %d  det %d  trk %d/%d",
                            captureMs, inferMs, droppedFrameCount,
                            detections.size(), activeTracks, totalTrackIds);

                    new Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if (overlayView != null) {
                            overlayView.setDebugInfo(debugLine);
                            overlayView.updateDetections(detections);
                        }
                        if (detectionCallback != null && !detections.isEmpty())
                            detectionCallback.onDetectionResult(detections);
                    });
                } finally {
                    isProcessingFrame.set(false);
                }
            }
        });
    }

    private void updateTextureViewTransform() {
        if (mVideoSurface == null) return;

        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        ((android.app.Activity) getContext()).getWindowManager()
                .getDefaultDisplay().getMetrics(dm);
        int parentW = dm.widthPixels;
        int parentH = dm.heightPixels;

        int newH = parentH;
        int newW = (int)(parentH * 16f / 9f);
        if (newW > parentW) {
            newW = parentW;
            newH = (int)(parentW * 9f / 16f);
        }

        FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(newW, newH);
        videoParams.gravity = android.view.Gravity.CENTER;
        mVideoSurface.setLayoutParams(videoParams);

        if (overlayView != null) {
            FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(newW, newH);
            overlayParams.gravity = android.view.Gravity.CENTER;
            overlayView.setLayoutParams(overlayParams);
        }
    }

}
