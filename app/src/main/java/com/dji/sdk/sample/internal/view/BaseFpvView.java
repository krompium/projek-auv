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
import com.dji.sdk.sample.internal.util.AdaptiveInterval;
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
    private int capturedFrameCount = 0;
    private MetricsLogger metricsLogger;

    // Adaptive capture gating (U9, R9): interval derived from the measured
    // cycle time; stretched on static scenes for thermal relief.
    private volatile long adaptiveIntervalMs = 400;
    private float emaCycleMs = 300f;
    private int[] prevSignature = null;
    private int staticStreak = 0;
    private static final int SIGNATURE_GRID = 8; // 8x8 luminance samples

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
                    // Warm-up inference (U9): absorbs GPU shader compilation /
                    // DVFS ramp so the first real frame isn't a jank spike.
                    try {
                        Bitmap warmup = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
                        long t0 = System.currentTimeMillis();
                        yoloDetector.detect(warmup);
                        warmup.recycle();
                        yoloDetector.reset(); // warm-up must not leave track state
                        if (metricsLogger != null) {
                            metricsLogger.logEvent("WARMUP",
                                    "first inference " + (System.currentTimeMillis() - t0) + " ms");
                        }
                    } catch (Throwable ignored) {
                    }
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
        if (now - lastCaptureStartTime < adaptiveIntervalMs) return;
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

                    // Static-scene signature BEFORE inference recycles the frame
                    int[] signature = computeSignature(frameBitmap);

                    // Measure inference time
                    long inferStart = System.currentTimeMillis();
                    List<YoloDetector.Detection> detections = yoloDetector.detect(frameBitmap);
                    final long inferMs = System.currentTimeMillis() - inferStart;
                    frameBitmap.recycle();

                    // Adaptive interval (U9): follow the measured cycle time,
                    // stretch on static scenes (thermal relief), snap back on motion.
                    emaCycleMs = AdaptiveInterval.updateEma(emaCycleMs, captureMs + inferMs);
                    boolean staticScene = AdaptiveInterval.isStatic(prevSignature, signature);
                    prevSignature = signature;
                    staticStreak = staticScene ? staticStreak + 1 : 0;
                    adaptiveIntervalMs = AdaptiveInterval.compute(emaCycleMs,
                            staticStreak >= AdaptiveInterval.STATIC_STREAK_THRESHOLD);

                    // File-based per-frame metrics (replaces Toast spam)
                    final int activeTracks = yoloDetector.getActiveTrackCount();
                    final int totalTrackIds = yoloDetector.getTotalTrackIdCount();
                    if (metricsLogger != null) {
                        metricsLogger.logFrame(System.currentTimeMillis(), captureMs, inferMs,
                                droppedFrameCount, detections.size(), activeTracks, totalTrackIds,
                                yoloDetector.isGpuActive(), adaptiveIntervalMs);
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

    /**
     * Cheap 8x8 luminance signature for static-scene detection (U9).
     * ~64 pixel reads per frame — negligible vs the 200 ms inference.
     */
    private static int[] computeSignature(Bitmap bmp) {
        if (bmp == null) return null;
        int[] sig = new int[SIGNATURE_GRID * SIGNATURE_GRID];
        int w = bmp.getWidth(), h = bmp.getHeight();
        int k = 0;
        for (int gy = 0; gy < SIGNATURE_GRID; gy++) {
            for (int gx = 0; gx < SIGNATURE_GRID; gx++) {
                int x = (int) ((gx + 0.5f) * w / SIGNATURE_GRID);
                int y = (int) ((gy + 0.5f) * h / SIGNATURE_GRID);
                int p = bmp.getPixel(x, y);
                sig[k++] = (((p >> 16) & 0xFF) * 3 + ((p >> 8) & 0xFF) * 6 + (p & 0xFF)) / 10;
            }
        }
        return sig;
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
