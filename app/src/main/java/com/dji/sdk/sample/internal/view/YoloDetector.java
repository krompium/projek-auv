package com.dji.sdk.sample.internal.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;

import com.dji.sdk.sample.internal.util.QuantUtil;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class YoloDetector {

    private static final String TAG = "YoloDetector";
    private static final String MODEL_FILE = "best.tflite";
    private static final String LABELS_FILE = "labels.txt";

    private static final int DEFAULT_INPUT_SIZE = 640;
    private static final int DEFAULT_MAX_DETECTIONS = 8400;

    // Gate 0.15f and 8-frame memory (~3.2s) with ByteTrack 2-stage recovery
    private final BoxTracker tracker = new BoxTracker(0.15f, 8);

    // Centralized in DetectionConfig (U7)
    private static final float CONFIDENCE_THRESHOLD =
            com.dji.sdk.sample.internal.util.DetectionConfig.CONFIDENCE_THRESHOLD;
    private static final float LOW_CONFIDENCE_THRESHOLD =
            com.dji.sdk.sample.internal.util.DetectionConfig.LOW_CONFIDENCE_THRESHOLD;
    private static final float IOU_THRESHOLD =
            com.dji.sdk.sample.internal.util.DetectionConfig.IOU_THRESHOLD;

    // Exact 256-entry LUT for c / 255.0f
    private static final float[] FLOAT_LUT = new float[256];
    static {
        for (int i = 0; i < 256; i++) {
            FLOAT_LUT[i] = i / 255.0f;
        }
    }

    private Interpreter tflite;
    private final List<String> labels = new ArrayList<>();
    private ByteBuffer inputBuffer;

    // Dynamically detected tensor dimensions (supports imgsz=640, 800, etc.)
    private int inputSize = DEFAULT_INPUT_SIZE;
    private int numDetections = DEFAULT_MAX_DETECTIONS;
    private boolean channelsFirst = false;

    // Pre-allocated buffers to eliminate ~1.8 MB per-frame GC allocation
    private int[] pixelBuffer;
    private float[][][] outputBuffer;
    private byte[][][] rawOutputBuffer;
    private final Rect srcRect = new Rect();
    private final Rect dstRect = new Rect();
    private final byte[] int8InputLut = new byte[256];
    private final float[] int8OutputLut = new float[256];

    // Reused letterbox target (allocation kept off the hot path)
    private Bitmap letterboxBitmap;
    private Canvas letterboxCanvas;
    /** YOLO-standard gray padding (114,114,114). */
    private static final int PAD_COLOR = Color.rgb(114, 114, 114);

    private float inputScale;
    private int inputZeroPoint;
    private float outputScale;
    private int outputZeroPoint;
    private boolean isInt8 = false;
    private long lastErrorToastMs = 0;
    private volatile String initError = null;
    private Context appContext;
    private GpuDelegate gpuDelegate = null;

    public static class Detection {
        public final RectF boundingBox;
        public final String label;
        public final float confidence;
        public int trackId;
        /** Consecutive matched cycles (>= MIN_TRACK_HITS means confirmed). */
        public int hits = 1;
        /** Track velocity in normalized units/second (for overlay prediction). */
        public float velocityX, velocityY;
        /** Wall-clock time (ms) the video frame was captured. */
        public long timestampMs;
        /** Wall-clock time (ms) inference completed and delivered this detection. */
        public long deliveryTimestampMs;

        public Detection(RectF boundingBox, String label, float confidence) {
            this.boundingBox = boundingBox;
            this.label = label;
            this.confidence = confidence;
        }

        public boolean isConfirmed() {
            return trackId > 0 && hits >= com.dji.sdk.sample.internal.util.DetectionConfig.MIN_TRACK_HITS;
        }
    }

    public YoloDetector(Context context) {
        try {
            this.appContext = context.getApplicationContext();
            loadLabels(context);
            MappedByteBuffer modelBuffer = loadModelFile(context);

            Interpreter.Options options = new Interpreter.Options();

            try (CompatibilityList compatList = new CompatibilityList()) {
                if (compatList.isDelegateSupportedOnThisDevice()) {
                    GpuDelegate.Options delegateOptions = compatList.getBestOptionsForThisDevice();
                    delegateOptions.setPrecisionLossAllowed(true);
                    delegateOptions.setInferencePreference(
                            GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED);
                    java.io.File cacheDir = context.getCodeCacheDir();
                    if (cacheDir != null && modelBuffer != null) {
                        delegateOptions.setSerializationParams(
                                cacheDir.getAbsolutePath(),
                                MODEL_FILE + "_" + modelBuffer.capacity());
                    }
                    gpuDelegate = new GpuDelegate(delegateOptions);
                    options.addDelegate(gpuDelegate);
                } else {
                    options.setNumThreads(4);
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                            android.widget.Toast.makeText(context,
                                    "GPU not supported — CPU fallback (4 threads)",
                                    android.widget.Toast.LENGTH_LONG).show());
                }
            }

            tflite = new Interpreter(modelBuffer, options);

            // Detect input spatial size & channel layout dynamically
            int[] inShape = tflite.getInputTensor(0).shape();
            if (inShape.length == 4) {
                channelsFirst = (inShape[1] == 3);
                inputSize = channelsFirst ? inShape[2] : inShape[1];
            } else {
                channelsFirst = false;
                inputSize = DEFAULT_INPUT_SIZE;
            }
            pixelBuffer = new int[inputSize * inputSize];

            // Fail loudly on model/label mismatch (R4)
            int[] outShape = tflite.getOutputTensor(0).shape(); // [1, 4+C, N]
            int modelClasses = (outShape.length >= 2) ? outShape[1] - 4 : -1;
            if (modelClasses != labels.size()) {
                String msg = "Model/label mismatch: model outputs " + modelClasses
                        + " class(es), labels.txt has " + labels.size();
                close();
                throw new IllegalStateException(msg);
            }
            numDetections = (outShape.length >= 3) ? outShape[2] : DEFAULT_MAX_DETECTIONS;
            outputBuffer = new float[1][4 + labels.size()][numDetections];

            // Detect if model is int8 quantized
            org.tensorflow.lite.DataType inputType =
                    tflite.getInputTensor(0).dataType();

            if (inputType == org.tensorflow.lite.DataType.UINT8
                    || inputType == org.tensorflow.lite.DataType.INT8) {
                isInt8 = true;

                // Read quantization params from model
                inputScale     = tflite.getInputTensor(0).quantizationParams().getScale();
                inputZeroPoint = tflite.getInputTensor(0).quantizationParams().getZeroPoint();
                outputScale     = tflite.getOutputTensor(0).quantizationParams().getScale();
                outputZeroPoint = tflite.getOutputTensor(0).quantizationParams().getZeroPoint();

                for (int v = 0; v < 256; v++) {
                    int8InputLut[v] = QuantUtil.quantizeChannel(v, inputScale, inputZeroPoint);
                    int signedByte = (byte) v;
                    int8OutputLut[v] = (signedByte - outputZeroPoint) * outputScale;
                }
                rawOutputBuffer = new byte[1][4 + labels.size()][numDetections];

                // int8: 1 byte per pixel channel
                inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3);
                Log.d(TAG, "int8 model detected. inputScale=" + inputScale
                        + " inputZP=" + inputZeroPoint
                        + " outputScale=" + outputScale
                        + " outputZP=" + outputZeroPoint);
            } else {
                isInt8 = false;
                // float32: 4 bytes per pixel channel
                inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4);
                Log.d(TAG, "float32 model detected");
            }

            inputBuffer.order(ByteOrder.nativeOrder());
            Log.d(TAG, "YoloDetector initialized. " + getModelInfo());

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize YoloDetector", e);
            final String err = e.getMessage();
            initError = (err != null) ? err : e.getClass().getSimpleName();
        }
    }

    private void loadLabels(Context context) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open(LABELS_FILE)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    labels.add(line.trim());
                }
            }
        }
        Log.d(TAG, "Loaded " + labels.size() + " labels: " + labels.toString());
    }

    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        try (android.content.res.AssetFileDescriptor fileDescriptor =
                     context.getAssets().openFd(MODEL_FILE);
             FileInputStream inputStream =
                     new FileInputStream(fileDescriptor.getFileDescriptor());
             FileChannel fileChannel = inputStream.getChannel()) {
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        }
    }

    private void bitmapToInputBuffer(Bitmap bitmap) {
        inputBuffer.rewind();
        bitmap.getPixels(pixelBuffer, 0, inputSize, 0, 0, inputSize, inputSize);

        final int plane = inputSize * inputSize;

        if (isInt8) {
            final byte[] lut = int8InputLut;
            for (int i = 0; i < plane; i++) {
                int pixel = pixelBuffer[i];
                inputBuffer.put(lut[(pixel >> 16) & 0xFF]);
                inputBuffer.put(lut[(pixel >> 8)  & 0xFF]);
                inputBuffer.put(lut[ pixel        & 0xFF]);
            }
        } else if (channelsFirst) {
            final float[] lut = FLOAT_LUT;
            for (int i = 0; i < plane; i++) {
                int pixel = pixelBuffer[i];
                inputBuffer.putFloat(i * 4,               lut[(pixel >> 16) & 0xFF]);
                inputBuffer.putFloat((plane + i) * 4,     lut[(pixel >> 8)  & 0xFF]);
                inputBuffer.putFloat((2 * plane + i) * 4, lut[ pixel        & 0xFF]);
            }
        } else {
            final float[] lut = FLOAT_LUT;
            for (int i = 0; i < plane; i++) {
                int pixel = pixelBuffer[i];
                inputBuffer.putFloat(lut[(pixel >> 16) & 0xFF]);
                inputBuffer.putFloat(lut[(pixel >> 8)  & 0xFF]);
                inputBuffer.putFloat(lut[ pixel        & 0xFF]);
            }
        }
    }

    public List<Detection> detect(Bitmap bitmap) {
        return detect(bitmap, System.currentTimeMillis());
    }

    public List<Detection> detect(Bitmap bitmap, long captureTimestampMs) {
        List<Detection> results = new ArrayList<>();
        if (tflite == null || bitmap == null) return results;

        try {
            // Aspect-preserving letterbox to the square model input (R2).
            Letterbox lb = Letterbox.compute(
                    bitmap.getWidth(), bitmap.getHeight(), inputSize);
            Bitmap modelInput;
            if (lb.isIdentity() && bitmap.getWidth() == inputSize) {
                modelInput = bitmap;
            } else {
                if (letterboxBitmap == null) {
                    letterboxBitmap = Bitmap.createBitmap(
                            inputSize, inputSize, Bitmap.Config.ARGB_8888);
                    letterboxCanvas = new Canvas(letterboxBitmap);
                }
                letterboxCanvas.drawColor(PAD_COLOR);
                srcRect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
                dstRect.set(lb.padX, lb.padY,
                        lb.padX + lb.contentWidth, lb.padY + lb.contentHeight);
                letterboxCanvas.drawBitmap(bitmap, srcRect, dstRect, null);
                modelInput = letterboxBitmap;
            }

            bitmapToInputBuffer(modelInput);

            int numClasses = labels.size();

            if (isInt8) {
                tflite.run(inputBuffer, rawOutputBuffer);
                final float[] outLut = int8OutputLut;
                for (int row = 0; row < 4 + numClasses; row++) {
                    byte[] rawRow = rawOutputBuffer[0][row];
                    float[] outRow = outputBuffer[0][row];
                    for (int col = 0; col < numDetections; col++) {
                        outRow[col] = outLut[rawRow[col] & 0xFF];
                    }
                }
            } else {
                tflite.run(inputBuffer, outputBuffer);
            }

            results = parseOutput(outputBuffer[0], numClasses, lb, captureTimestampMs);

        } catch (Exception e) {
            Log.e(TAG, "Inference error", e);
            final String errMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
            long nowErr = System.currentTimeMillis();
            if (appContext != null && nowErr - lastErrorToastMs > 10_000) {
                lastErrorToastMs = nowErr;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(appContext, "Detect error: " + errMsg,
                                android.widget.Toast.LENGTH_LONG).show());
            }
        }

        return results;
    }

    private List<Detection> parseOutput(float[][] output, int numClasses,
                                        Letterbox lb, long captureTimestampMs) {
        List<Detection> detections = new ArrayList<>();

        for (int i = 0; i < numDetections; i++) {
            float maxScore = 0;
            int bestClass = -1;

            for (int c = 0; c < numClasses; c++) {
                float score = output[4 + c][i];
                if (score > maxScore) {
                    maxScore = score;
                    bestClass = c;
                }
            }
            // Collect candidates down to LOW_CONFIDENCE_THRESHOLD for ByteTrack Stage 2
            float minGate = (tracker.getActiveCount() > 0)
                    ? LOW_CONFIDENCE_THRESHOLD : CONFIDENCE_THRESHOLD;
            if (maxScore < minGate || bestClass < 0) continue;

            float cx = output[0][i];
            float cy = output[1][i];
            float w  = output[2][i];
            float h  = output[3][i];

            float[] box = new float[]{
                    cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f};
            if (Math.max(box[2], box[3]) > 2.0f) {
                box[0] /= inputSize;
                box[1] /= inputSize;
                box[2] /= inputSize;
                box[3] /= inputSize;
            }
            lb.inverseMapNormalized(box);
            if (box[2] <= box[0] || box[3] <= box[1]) continue;

            String label = (bestClass < labels.size()) ?
                    labels.get(bestClass) : "unknown";
            detections.add(new Detection(
                    new RectF(box[0], box[1], box[2], box[3]), label, maxScore));
        }

        return applyNMS(detections, captureTimestampMs);
    }

    private List<Detection> applyNMS(List<Detection> detections, long captureTimestampMs) {
        detections.sort((a, b) -> Float.compare(b.confidence, a.confidence));

        List<Detection> result = new ArrayList<>();
        boolean[] suppressed = new boolean[detections.size()];

        for (int i = 0; i < detections.size(); i++) {
            if (suppressed[i]) continue;
            result.add(detections.get(i));
            for (int j = i + 1; j < detections.size(); j++) {
                if (!suppressed[j]) {
                    if (computeIoU(detections.get(i).boundingBox,
                            detections.get(j).boundingBox) > IOU_THRESHOLD) {
                        suppressed[j] = true;
                    }
                }
            }
        }
        assignTrackIds(result, captureTimestampMs);

        return result;
    }

    private float computeIoU(RectF a, RectF b) {
        float interLeft   = Math.max(a.left,   b.left);
        float interTop    = Math.max(a.top,    b.top);
        float interRight  = Math.min(a.right,  b.right);
        float interBottom = Math.min(a.bottom, b.bottom);

        if (interRight <= interLeft || interBottom <= interTop) return 0f;

        float interArea = (interRight - interLeft) * (interBottom - interTop);
        float aArea = (a.right - a.left) * (a.bottom - a.top);
        float bArea = (b.right - b.left) * (b.bottom - b.top);

        return interArea / (aArea + bArea - interArea);
    }

    public synchronized void close() {
        if (tflite != null) {
            tflite.close();
            tflite = null;
        }
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }
        if (letterboxBitmap != null) {
            letterboxBitmap.recycle();
            letterboxBitmap = null;
            letterboxCanvas = null;
        }
    }

    private void assignTrackIds(List<Detection> currentDetections, long captureTimestampMs) {
        long deliveryMs = System.currentTimeMillis();
        long trackTimeMs = (captureTimestampMs > 0) ? captureTimestampMs : deliveryMs;
        int n = currentDetections.size();
        List<float[]> boxes = new ArrayList<>(n);
        float[] scores = new float[n];
        for (int i = 0; i < n; i++) {
            Detection d = currentDetections.get(i);
            boxes.add(new float[]{
                    d.boundingBox.left, d.boundingBox.top,
                    d.boundingBox.right, d.boundingBox.bottom});
            scores[i] = d.confidence;
        }

        List<BoxTracker.Assignment> assignments =
                tracker.assign(boxes, scores, CONFIDENCE_THRESHOLD, trackTimeMs);
        for (int i = n - 1; i >= 0; i--) {
            Detection d = currentDetections.get(i);
            BoxTracker.Assignment a = assignments.get(i);
            if (a.trackId <= 0) {
                // Unassociated low-confidence candidate: discard immediately
                currentDetections.remove(i);
                continue;
            }
            d.trackId = a.trackId;
            d.hits = a.hits;
            d.velocityX = a.vx;
            d.velocityY = a.vy;
            d.timestampMs = trackTimeMs;
            d.deliveryTimestampMs = deliveryMs;
        }
    }

    public void reset() {
        tracker.reset();
    }

    public int getActiveTrackCount() {
        return tracker.getActiveCount();
    }

    public int getTotalTrackIdCount() {
        return tracker.getTotalCreated();
    }

    public boolean isReady() {
        return tflite != null && initError == null;
    }

    public String getInitError() {
        return initError;
    }

    public String getModelInfo() {
        if (tflite == null) return "not loaded";
        return "in=" + java.util.Arrays.toString(tflite.getInputTensor(0).shape())
                + " out=" + java.util.Arrays.toString(tflite.getOutputTensor(0).shape())
                + " classes=" + labels.size() + " gpu=" + (gpuDelegate != null);
    }

    public boolean isGpuActive() {
        return gpuDelegate != null;
    }
}