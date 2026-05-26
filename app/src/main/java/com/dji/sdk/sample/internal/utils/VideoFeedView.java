package com.dji.sdk.sample.internal.utils;

import android.content.Context;
import android.graphics.Matrix;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import androidx.annotation.NonNull;
import dji.midware.usb.P3.UsbAccessoryService;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.thirdparty.rx.Observable;
import dji.thirdparty.rx.Subscription;
import dji.thirdparty.rx.android.schedulers.AndroidSchedulers;
import dji.thirdparty.rx.functions.Action1;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import com.dji.sdk.sample.internal.view.OverlayView;
import com.dji.sdk.sample.internal.view.YoloDetector;

/**
 * VideoView will show the live video for the given video feed.
 */
public class VideoFeedView extends SurfaceView {
    //region Properties
    private final static String TAG = "DULFpvWidget";
    private DJICodecManager codecManager = null;
    private VideoFeeder.VideoDataListener videoDataListener = null;
    private int videoWidth;
    private int videoHeight;
    private boolean isPrimaryVideoFeed;
    private View coverView;
    private final long WAIT_TIME = 500; // Half of a second
    private AtomicLong lastReceivedFrameTime = new AtomicLong(0);
    private Observable timer =
        Observable.timer(100, TimeUnit.MICROSECONDS).observeOn(AndroidSchedulers.mainThread()).repeat();
    private Subscription subscription;
    private SurfaceHolder surfaceHolder;

    private HandlerThread processingThread;
    private Handler processingHandler;
    private volatile boolean isProcessingFrame = false;
    private YoloDetector yoloDetector;
    private OverlayView overlayView;

    //endregion

    //region Life-Cycle
    public VideoFeedView(Context context) {
        this(context, null, 0);
    }

    public VideoFeedView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VideoFeedView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    public void setCoverView(View view) {
        coverView = view;
    }

    private void init(Context context) {
        // Avoid the rending exception in the Android Studio Preview view.
        if (isInEditMode()) {
            return;
        }
        try {
            processingThread = new HandlerThread("YuvProcessingThread");
            processingThread.start();
            processingHandler = new Handler(processingThread.getLooper());

            surfaceHolder = getHolder();
            surfaceHolder.addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(@NonNull SurfaceHolder surfaceHolder) {
                    if (codecManager == null) {
                        codecManager = new DJICodecManager(context,
                                surfaceHolder,
                                getWidth(),
                                getHeight(),
                                isPrimaryVideoFeed
                                        ? UsbAccessoryService.VideoStreamSource.Camera
                                        : UsbAccessoryService.VideoStreamSource.Fpv);

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
                                final int frameW = width;
                                final int frameH = height;

                                processingHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            Bitmap bmp = convertYuvToBitmap(yuvBytes, frameW, frameH);
                                            if (bmp != null && yoloDetector != null) {
                                                List<YoloDetector.Detection> detections = yoloDetector.detect(bmp);
                                                bmp.recycle();
                                                final List<YoloDetector.Detection> finalDetections = detections;
                                                new Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        android.widget.Toast.makeText(getContext(),
                                                                "Detections: " + finalDetections.size(),
                                                                android.widget.Toast.LENGTH_SHORT).show();
                                                        if (overlayView != null) {
                                                            overlayView.updateDetections(finalDetections);
                                                        }
                                                    }
                                                });
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
                public void surfaceChanged(@NonNull SurfaceHolder surfaceHolder, int format, int width, int height) {

                }

                @Override
                public void surfaceDestroyed(@NonNull SurfaceHolder surfaceHolder) {
                    if (codecManager != null) {
                        codecManager.cleanSurface();
                        codecManager.destroyCodec();
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
                }
            });

            videoDataListener = new VideoFeeder.VideoDataListener() {

                @Override
                public void onReceive(byte[] videoBuffer, int size) {

                    lastReceivedFrameTime.set(System.currentTimeMillis());

                    if (codecManager != null) {
                        codecManager.sendDataToDecoder(videoBuffer,
                                                       size,
                                                       isPrimaryVideoFeed
                                                       ? UsbAccessoryService.VideoStreamSource.Camera.getIndex()
                                                       : UsbAccessoryService.VideoStreamSource.Fpv.getIndex());
                    }
                }
            };

            subscription = timer.subscribe(new Action1() {
                @Override
                public void call(Object o) {
                    final long now = System.currentTimeMillis();
                    final long ellapsedTime = now - lastReceivedFrameTime.get();
                    if (coverView != null) {
                        if (ellapsedTime > WAIT_TIME && !ModuleVerificationUtil.isMavic2Product()) {
                            if (coverView.getVisibility() == INVISIBLE) {
                                coverView.setVisibility(VISIBLE);
                            }
                        } else {
                            if (coverView.getVisibility() == VISIBLE) {
                                coverView.setVisibility(INVISIBLE);
                            }
                        }
                    }
                }
            });
        } catch (Throwable e) {
            android.widget.Toast.makeText(context,
                    "CRASH: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    public VideoFeeder.VideoDataListener registerLiveVideo(VideoFeeder.VideoFeed videoFeed, boolean isPrimary) {
        isPrimaryVideoFeed = isPrimary;

        if (videoDataListener != null && videoFeed != null && !videoFeed.getListeners().contains(videoDataListener)) {
            videoFeed.addVideoDataListener(videoDataListener);
            return videoDataListener;
        }
        return null;
    }

    public void changeSourceResetKeyFrame() {
        if (codecManager != null) {
            codecManager.resetKeyFrame();
        }
    }

    public void setOverlayView(OverlayView overlay) {
        this.overlayView = overlay;

        if (overlay != null && yoloDetector == null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        yoloDetector = new YoloDetector(getContext().getApplicationContext());
                        new Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                android.widget.Toast.makeText(getContext(),
                                        "YOLO loaded!", android.widget.Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Throwable e) {
                        final String error = e.getClass().getSimpleName() + ": " + e.getMessage();
                        new Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
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
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (subscription != null && !subscription.isUnsubscribed()) {
            subscription.unsubscribe();
        }
        VideoFeeder.getInstance().destroy();
    }

    private Bitmap convertYuvToBitmap(byte[] yuvBytes, int width, int height) {
        int ySize = width * height;
        byte[] nv21 = new byte[yuvBytes.length];
        System.arraycopy(yuvBytes, 0, nv21, 0, ySize);
        for (int i = ySize; i < yuvBytes.length - 1; i += 2) {
            nv21[i]     = yuvBytes[i + 1];
            nv21[i + 1] = yuvBytes[i];
        }
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 85, outputStream);
        byte[] jpegBytes = outputStream.toByteArray();
        Bitmap fullBitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        if (fullBitmap == null) return null;
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(fullBitmap, 640, 640, true);
        fullBitmap.recycle();
        return scaledBitmap;
    }
}
