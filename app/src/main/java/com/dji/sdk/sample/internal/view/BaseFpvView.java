package com.dji.sdk.sample.internal.view;

import android.app.Service;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.widget.FrameLayout;
import com.dji.sdk.sample.R;
import dji.midware.usb.P3.UsbAccessoryService;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;

import android.media.MediaFormat;
import android.util.Log;
import java.nio.ByteBuffer;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Handler;
import android.os.HandlerThread;
import java.io.ByteArrayOutputStream;

import java.util.List;

/**
 * This class is designed for showing the fpv video feed from the camera or Lightbridge 2.
 */
public class BaseFpvView extends FrameLayout implements TextureView.SurfaceTextureListener{
    private VideoFeeder.VideoDataListener videoDataListener = null;
    private DJICodecManager codecManager = null;

    private HandlerThread processingThread;
    private Handler processingHandler;
    private volatile boolean isProcessingFrame = false;
    public volatile Bitmap lastDetectionBitmap = null;

    private YoloDetector yoloDetector;

    private OverlayView overlayView;

    public BaseFpvView(Context context, AttributeSet attrs) {
        super(context, attrs);

        initUI();
    }

    private void initUI() {
        processingThread = new HandlerThread("YuvProcessingThread");
        processingThread.start();
        processingHandler = new Handler(processingThread.getLooper());

        yoloDetector = new YoloDetector(getContext().getApplicationContext());

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    yoloDetector = new YoloDetector(getContext().getApplicationContext());
                    android.os.Handler mainHandler = new android.os.Handler(
                            android.os.Looper.getMainLooper());
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            android.widget.Toast.makeText(getContext(),
                                    "YOLO model loaded OK!",
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Throwable e) {
                    android.os.Handler mainHandler = new android.os.Handler(
                            android.os.Looper.getMainLooper());
                    final String error = e.getClass().getSimpleName() + ": " + e.getMessage();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            android.widget.Toast.makeText(getContext(),
                                    "YOLO FAILED: " + error,
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();

        LayoutInflater layoutInflater = (LayoutInflater) getContext().getSystemService(Service.LAYOUT_INFLATER_SERVICE);

        layoutInflater.inflate(R.layout.view_fpv_and_camera_display, this, true);

        TextureView mVideoSurface = (TextureView) findViewById(R.id.texture_video_previewer_surface);

        if (null != mVideoSurface) {
            mVideoSurface.setSurfaceTextureListener(this);

            videoDataListener = new VideoFeeder.VideoDataListener() {
                @Override
                public void onReceive(byte[] bytes, int size) {
                    if (null != codecManager) {
                        codecManager.sendDataToDecoder(bytes,
                                                       size,
                                                       UsbAccessoryService.VideoStreamSource.Fpv.getIndex());
                    }
                }
            };
        }

        overlayView = findViewById(R.id.detection_overlay);

        initSDKCallback();
    }

    private void initSDKCallback() {
        try {
            VideoFeeder.getInstance().getPrimaryVideoFeed().addVideoDataListener(videoDataListener);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (codecManager == null) {
            codecManager = new DJICodecManager(getContext().getApplicationContext(),
                                               surface,
                                               width,
                                               height,
                                               UsbAccessoryService.VideoStreamSource.Fpv);

            codecManager.enabledYuvData(true);
            codecManager.setYuvDataCallback(new DJICodecManager.YuvDataCallback() {
                @Override
                public void onYuvDataReceived(MediaFormat format, ByteBuffer yuvFrame,
                                              int dataSize, int width, int height) {
                    if (yuvFrame == null || dataSize <= 0) return;

                    if (isProcessingFrame) return;
                    isProcessingFrame = true;

                    final byte[] yuvBytes = new byte[dataSize];
                    yuvFrame.get(yuvBytes, 0, dataSize);
                    final int frameWidth = width;
                    final int frameHeight = height;

                    processingHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Bitmap bmp = convertYuvToBitmap(yuvBytes, frameWidth, frameHeight);
                                if (bmp != null && yoloDetector != null) {
                                    List<YoloDetector.Detection> detections = yoloDetector.detect(bmp);
                                    lastDetectionBitmap = bmp;
                                    final int count = detections.size();
                                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                                        @Override
                                        public void run() {
                                            android.widget.Toast.makeText(getContext(),
                                                    "Detections: " + count,
                                                    android.widget.Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                    if (overlayView != null) {
                                        overlayView.updateDetections(detections);
                                    }
                                }
                            } finally {
                                isProcessingFrame = false;
                            }
                        }
                    });
                }
            });
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

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

    }

    private Bitmap convertYuvToBitmap(byte[] yuvBytes, int width, int height) {
        // DJI outputs NV12 (YUV420SP). Android's YuvImage needs NV21.
        // We swap the UV planes: NV12 = Y...UVUV, NV21 = Y...VUVU
        int ySize = width * height;
        byte[] nv21 = new byte[yuvBytes.length];

        // Copy Y plane directly
        System.arraycopy(yuvBytes, 0, nv21, 0, ySize);

        // Swap U and V bytes to convert NV12 → NV21
        for (int i = ySize; i < yuvBytes.length - 1; i += 2) {
            nv21[i]     = yuvBytes[i + 1]; // V
            nv21[i + 1] = yuvBytes[i];     // U
        }

        // Convert NV21 byte array → JPEG → Bitmap
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 85, outputStream);
        byte[] jpegBytes = outputStream.toByteArray();

        Bitmap fullBitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        if (fullBitmap == null) return null;

        // Scale down to 640x640 for YOLOv8n input
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(fullBitmap, 640, 640, true);
        fullBitmap.recycle();

        return scaledBitmap;
    }
}
