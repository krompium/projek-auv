package com.dji.sdk.sample.internal.view;

import android.app.Service;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.widget.FrameLayout;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.util.AdaptiveInterval;
import com.dji.sdk.sample.internal.util.MetricsLogger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import dji.midware.usb.P3.UsbAccessoryService;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;

public class BaseFpvView extends FrameLayout implements TextureView.SurfaceTextureListener {

    private static final int CAPTURE_WIDTH = 640;
    private static final int CAPTURE_HEIGHT = 360;

    private VideoFeeder.VideoDataListener videoDataListener = null;
    private DJICodecManager codecManager = null;
    private TextureView mVideoSurface = null;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread processingThread;
    private Handler processingHandler;

    /**
     * Frame gate. compareAndSet makes the capture decision atomic: only one
     * frame is ever captured/processed at a time, making reusableCaptureBitmap
     * completely thread-safe between capture on UI thread and read on worker.
     */
    private final AtomicBoolean isProcessingFrame = new AtomicBoolean(false);
    private volatile YoloDetector yoloDetector = null;
    private OverlayView overlayView;

    // Pre-allocated mutable bitmap reused across cycles (Zero-Allocation capture)
    private Bitmap reusableCaptureBitmap = null;
    private final int[] sigPixelRow = new int[CAPTURE_WIDTH];

    private int droppedFrameCount = 0;
    private long lastCaptureStartTime = 0;
    private int capturedFrameCount = 0;
    private volatile long lastCaptureMs = 0;
    private volatile long lastInferMs = 0;
    private MetricsLogger metricsLogger;

    public MetricsLogger getMetricsLogger() {
        return metricsLogger;
    }

    /** Executes disk I/O diagnostics on the background processing thread, never UI thread. */
    public void postIoTask(Runnable task) {
        Handler h = processingHandler;
        if (h != null && task != null) {
            h.post(task);
        }
    }

    public long getLastCaptureMs() {
        return lastCaptureMs;
    }

    public long getLastInferMs() {
        return lastInferMs;
    }

    public int getDroppedFrameCount() {
        return droppedFrameCount;
    }

    // Adaptive capture gating (U9, R9): interval derived from the measured
    // cycle time; stretched on static scenes for thermal relief.
    private volatile long adaptiveIntervalMs = 400;
    private float emaCycleMs = 300f;
    private static final int SIGNATURE_GRID = 8; // 8x8 luminance samples
    private int[] prevSignature = null;
    private final int[] currSignature = new int[SIGNATURE_GRID * SIGNATURE_GRID];
    private int staticStreak = 0;

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

        // Pre-allocate reusable capture bitmap once
        reusableCaptureBitmap = Bitmap.createBitmap(
                CAPTURE_WIDTH, CAPTURE_HEIGHT, Bitmap.Config.ARGB_8888);

        // Start background processing thread
        processingThread = new HandlerThread("YuvProcessingThread");
        processingThread.start();
        processingHandler = new Handler(processingThread.getLooper());

        // Load YOLO model and complete multi-pass JIT + GPU warm-up BEFORE
        // exposing yoloDetector to the UI thread's onSurfaceTextureUpdated.
        processingHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    YoloDetector localDetector =
                            new YoloDetector(getContext().getApplicationContext());
                    final boolean ready = localDetector.isReady();
                    final String initErr = localDetector.getInitError();
                    if (metricsLogger != null) {
                        metricsLogger.logEvent("MODEL", ready
                                ? localDetector.getModelInfo()
                                : "INIT FAILED: " + initErr);
                    }
                    if (ready) {
                        // 8-pass warm-up: forces Android ART JIT to compile the
                        // 409,600-pixel input loop & 8,400-anchor output loop to native
                        // code and warms up Mali-G57 GPU shaders before live video starts.
                        try {
                            Bitmap warmup = Bitmap.createBitmap(
                                    CAPTURE_WIDTH, CAPTURE_HEIGHT, Bitmap.Config.ARGB_8888);
                            long t0 = System.currentTimeMillis();
                            for (int i = 0; i < 2; i++) {
                                localDetector.detect(warmup);
                            }
                            warmup.recycle();
                            localDetector.reset();
                            if (metricsLogger != null) {
                                metricsLogger.logEvent("WARMUP",
                                        "8-pass JIT+GPU warmup " + (System.currentTimeMillis() - t0) + " ms");
                            }
                        } catch (Throwable ignored) {
                        }
                        // Offline parity harness (U6)
                        DetectionHarness.runIfRequested(
                                getContext().getApplicationContext(), localDetector, metricsLogger);
                        localDetector.reset();
                    }
                    // Expose detector to UI thread ONLY after warm-up is 100% done
                    yoloDetector = localDetector;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (ready) {
                                android.widget.Toast.makeText(getContext(),
                                        "YOLO loaded & warmed up!",
                                        android.widget.Toast.LENGTH_SHORT).show();
                            } else {
                                if (overlayView != null) {
                                    overlayView.setDebugInfo("MODEL ERROR: " + initErr);
                                }
                                new android.app.AlertDialog.Builder(getContext())
                                        .setTitle("Model gagal dimuat")
                                        .setMessage(String.valueOf(initErr))
                                        .setPositiveButton("OK", null)
                                        .show();
                            }
                        }
                    });
                } catch (Throwable e) {
                    final String err = e.getClass().getSimpleName() + ": " + e.getMessage();
                    mainHandler.post(new Runnable() {
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
            if (videoDataListener != null) {
                VideoFeeder.getInstance()
                        .getPrimaryVideoFeed()
                        .addVideoDataListener(videoDataListener);
            }
        } catch (Exception ignored) {
        }
    }

    private void removeSDKCallback() {
        try {
            if (videoDataListener != null) {
                VideoFeeder.getInstance()
                        .getPrimaryVideoFeed()
                        .removeVideoDataListener(videoDataListener);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (codecManager == null) {
            // Keep 1920x1080 as verified in BUG-06 to prevent border artifacts
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
        removeSDKCallback();
        if (codecManager != null) {
            codecManager.cleanSurface();
            codecManager = null;
        }
        final YoloDetector detectorToClose = yoloDetector;
        yoloDetector = null;
        final Bitmap bitmapToRecycle = reusableCaptureBitmap;
        reusableCaptureBitmap = null;

        if (processingHandler != null && processingThread != null) {
            processingHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (detectorToClose != null) {
                        detectorToClose.close();
                    }
                    if (bitmapToRecycle != null && !bitmapToRecycle.isRecycled()) {
                        bitmapToRecycle.recycle();
                    }
                }
            });
            processingThread.quitSafely();
            processingThread = null;
            processingHandler = null;
        } else {
            if (detectorToClose != null) detectorToClose.close();
            if (bitmapToRecycle != null && !bitmapToRecycle.isRecycled()) {
                bitmapToRecycle.recycle();
            }
        }
        if (overlayView != null) {
            overlayView.updateDetections(new ArrayList<YoloDetector.Detection>());
            overlayView = null;
        }
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        if (yoloDetector == null || !yoloDetector.isReady()) return;
        if (mVideoSurface == null || reusableCaptureBitmap == null) return;

        long now = System.currentTimeMillis();
        if (now - lastCaptureStartTime < adaptiveIntervalMs) return;
        lastCaptureStartTime = now;

        // Atomic frame gate: exactly one frame in flight, others count as dropped.
        if (!isProcessingFrame.compareAndSet(false, true)) {
            droppedFrameCount++;
            return;
        }
        capturedFrameCount++;

        // Zero-allocation capture into pre-allocated 640x360 Bitmap on UI thread.
        long captureStart = System.currentTimeMillis();
        final Bitmap frameBitmap = mVideoSurface.getBitmap(reusableCaptureBitmap);
        final long captureMs = System.currentTimeMillis() - captureStart;

        Handler handler = processingHandler;
        if (handler == null) {
            isProcessingFrame.set(false);
            return;
        }

        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    YoloDetector detector = yoloDetector;
                    if (frameBitmap == null || frameBitmap.isRecycled() || detector == null) {
                        if (metricsLogger != null && capturedFrameCount % 25 == 1) {
                            metricsLogger.logEvent("CAPTURE", "getBitmap returned null or closed");
                        }
                        return;
                    }

                    // Zero-allocation static-scene signature
                    computeSignatureInPlace(frameBitmap, currSignature);

                    // Measure inference time; pass captureStart so tracker & overlay
                    // compensate for the ~210 ms inference latency.
                    long inferStart = System.currentTimeMillis();
                    final List<YoloDetector.Detection> detections =
                            detector.detect(frameBitmap, captureStart);
                    final long inferMs = System.currentTimeMillis() - inferStart;

                    // Adaptive interval (U9): follow the measured cycle time,
                    // stretch on static scenes (thermal relief), snap back on motion.
                    emaCycleMs = AdaptiveInterval.updateEma(emaCycleMs, captureMs + inferMs);
                    boolean staticScene = AdaptiveInterval.isStatic(prevSignature, currSignature);
                    if (prevSignature == null) {
                        prevSignature = new int[SIGNATURE_GRID * SIGNATURE_GRID];
                    }
                    System.arraycopy(currSignature, 0, prevSignature, 0, currSignature.length);
                    staticStreak = staticScene ? staticStreak + 1 : 0;
                    adaptiveIntervalMs = Math.max(400L, AdaptiveInterval.compute(emaCycleMs,
                            staticStreak >= AdaptiveInterval.STATIC_STREAK_THRESHOLD));

                    lastCaptureMs = captureMs;
                    lastInferMs = inferMs;

                    int confirmedCount = 0;
                    for (YoloDetector.Detection d : detections) {
                        if (d.isConfirmed()) confirmedCount++;
                    }
                    final int confirmedDetCount = confirmedCount;

                    // File-based per-frame metrics
                    final int activeTracks = detector.getActiveTrackCount();
                    final int totalTrackIds = detector.getTotalTrackIdCount();
                    if (metricsLogger != null) {
                        metricsLogger.logFrame(System.currentTimeMillis(), captureMs, inferMs,
                                droppedFrameCount, confirmedDetCount, activeTracks, totalTrackIds,
                                detector.isGpuActive(), adaptiveIntervalMs);
                    }
                    final String debugLine = String.format(Locale.US,
                            "cap %dms  inf %dms  drop %d  det %d  trk %d/%d",
                            captureMs, inferMs, droppedFrameCount,
                            confirmedDetCount, activeTracks, totalTrackIds);

                    mainHandler.post(() -> {
                        if (overlayView != null) {
                            overlayView.setDebugInfo(debugLine);
                            overlayView.updateDetections(detections);
                        }
                        if (detectionCallback != null) {
                            detectionCallback.onDetectionResult(detections);
                        }
                    });
                } finally {
                    isProcessingFrame.set(false);
                }
            }
        });
    }

    /**
     * Zero-allocation 8x8 luminance signature for static-scene detection (U9).
     */
    private void computeSignatureInPlace(Bitmap bmp, int[] outSig) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int k = 0;
        for (int gy = 0; gy < SIGNATURE_GRID; gy++) {
            int y = (int) ((gy + 0.5f) * h / SIGNATURE_GRID);
            bmp.getPixels(sigPixelRow, 0, w, 0, y, w, 1);
            for (int gx = 0; gx < SIGNATURE_GRID; gx++) {
                int x = (int) ((gx + 0.5f) * w / SIGNATURE_GRID);
                int p = sigPixelRow[x];
                outSig[k++] = (((p >> 16) & 0xFF) * 3 + ((p >> 8) & 0xFF) * 6 + (p & 0xFF)) / 10;
            }
        }
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

        android.view.ViewGroup.LayoutParams currentVideoLp = mVideoSurface.getLayoutParams();
        if (currentVideoLp == null || currentVideoLp.width != newW || currentVideoLp.height != newH) {
            FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(newW, newH);
            videoParams.gravity = android.view.Gravity.CENTER;
            mVideoSurface.setLayoutParams(videoParams);
        }

        if (overlayView != null) {
            android.view.ViewGroup.LayoutParams currentOverlayLp = overlayView.getLayoutParams();
            if (currentOverlayLp == null || currentOverlayLp.width != newW || currentOverlayLp.height != newH) {
                FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(newW, newH);
                overlayParams.gravity = android.view.Gravity.CENTER;
                overlayView.setLayoutParams(overlayParams);
            }
        }
    }
}