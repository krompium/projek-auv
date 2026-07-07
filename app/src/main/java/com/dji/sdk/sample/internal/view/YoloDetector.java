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
    private static final String MODEL_FILE = "best_float16.tflite";
    private static final String LABELS_FILE = "labels.txt";

    private static final int INPUT_SIZE = 640;
    private final BoxTracker tracker = new BoxTracker();
    private static final int MAX_DETECTIONS = 8400;
    // Centralized in DetectionConfig (U7): one tunable, calibrated via the
    // U6 harness sweep, instead of magic numbers scattered across files.
    private static final float CONFIDENCE_THRESHOLD =
            com.dji.sdk.sample.internal.util.DetectionConfig.CONFIDENCE_THRESHOLD;
    private static final float IOU_THRESHOLD =
            com.dji.sdk.sample.internal.util.DetectionConfig.IOU_THRESHOLD;

    private Interpreter tflite;
    private List<String> labels = new ArrayList<>();
    private ByteBuffer inputBuffer;

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
    private GpuDelegate gpuDelegate = null;

    public static class Detection {
        public final RectF boundingBox;
        public final String label;
        public final float confidence;
        public int trackId;
        /** Track velocity in normalized units/second (for overlay prediction). */
        public float velocityX, velocityY;
        /** Wall-clock time (ms) this detection was produced. */
        public long timestampMs;

        public Detection(RectF boundingBox, String label, float confidence) {
            this.boundingBox = boundingBox;
            this.label = label;
            this.confidence = confidence;
        }
    }

    public YoloDetector(Context context) {
        try {
            loadLabels(context);
            MappedByteBuffer modelBuffer = loadModelFile(context);

            Interpreter.Options options = new Interpreter.Options();

            try (CompatibilityList compatList = new CompatibilityList()) {
                if (compatList.isDelegateSupportedOnThisDevice()) {
                    GpuDelegate.Options delegateOptions = compatList.getBestOptionsForThisDevice();
                    gpuDelegate = new GpuDelegate(delegateOptions);
                    options.addDelegate(gpuDelegate);
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                            android.widget.Toast.makeText(context,
                                    "GPU Delegate: Active", android.widget.Toast.LENGTH_LONG).show());
                } else {
                    options.setNumThreads(4);
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                            android.widget.Toast.makeText(context,
                                    "GPU not supported — CPU fallback (4 threads)", android.widget.Toast.LENGTH_LONG).show());
                }
            }

            tflite = new Interpreter(modelBuffer, options);

            // Fail loudly on model/label mismatch (R4): a silent mismatch
            // corrupts parseOutput's class-score indexing.
            int[] outShape = tflite.getOutputTensor(0).shape(); // [1, 4+C, 8400]
            int modelClasses = (outShape.length >= 2) ? outShape[1] - 4 : -1;
            if (modelClasses != labels.size()) {
                String msg = "Model/label mismatch: model outputs " + modelClasses
                        + " class(es), labels.txt has " + labels.size();
                tflite.close();
                tflite = null;
                throw new IllegalStateException(msg);
            }

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

                // int8: 1 byte per pixel
                inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3);
                Log.d(TAG, "int8 model detected. inputScale=" + inputScale
                        + " inputZP=" + inputZeroPoint
                        + " outputScale=" + outputScale
                        + " outputZP=" + outputZeroPoint);
            } else {
                isInt8 = false;
                // float32: 4 bytes per pixel
                inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4);
                Log.d(TAG, "float32 model detected");
            }

            inputBuffer.order(ByteOrder.nativeOrder());
            Log.d(TAG, "YoloDetector initialized. Classes=" + labels.size()
                    + " isInt8=" + isInt8);

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize YoloDetector", e);
            final String err = e.getMessage();
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    android.widget.Toast.makeText(context,
                            "YOLO init error: " + err,
                            android.widget.Toast.LENGTH_LONG).show());
        }
    }

    private void loadLabels(Context context) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open(LABELS_FILE)));
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.trim().isEmpty()) {
                labels.add(line.trim());
            }
        }
        reader.close();
        Log.d(TAG, "Loaded " + labels.size() + " labels: " + labels.toString());
    }

    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        android.content.res.AssetFileDescriptor fileDescriptor =
                context.getAssets().openFd(MODEL_FILE);
        FileInputStream inputStream =
                new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    private void bitmapToInputBuffer(Bitmap bitmap) {
        inputBuffer.rewind();
        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        for (int pixel : pixels) {
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8)  & 0xFF;
            int b =  pixel        & 0xFF;

            if (isInt8) {
                // Correct affine quantization: round((c/255)/scale) + zeroPoint.
                // The old code wrote (byte)(c + zeroPoint), which is wrong for
                // any real scale and broke the int8 path (R4).
                inputBuffer.put(QuantUtil.quantizeChannel(r, inputScale, inputZeroPoint));
                inputBuffer.put(QuantUtil.quantizeChannel(g, inputScale, inputZeroPoint));
                inputBuffer.put(QuantUtil.quantizeChannel(b, inputScale, inputZeroPoint));
            } else {
                inputBuffer.putFloat(r / 255.0f);
                inputBuffer.putFloat(g / 255.0f);
                inputBuffer.putFloat(b / 255.0f);
            }
        }
    }

    public List<Detection> detect(Bitmap bitmap) {
        List<Detection> results = new ArrayList<>();
        if (tflite == null || bitmap == null) return results;

        try {
            // Aspect-preserving letterbox to the square model input (R2).
            // getBitmap(640, 640) used to squash 16:9 into a square, which is
            // distorted relative to YOLOv8's letterboxed training input.
            Letterbox lb = Letterbox.compute(
                    bitmap.getWidth(), bitmap.getHeight(), INPUT_SIZE);
            Bitmap modelInput;
            if (lb.isIdentity() && bitmap.getWidth() == INPUT_SIZE) {
                modelInput = bitmap;
            } else {
                if (letterboxBitmap == null) {
                    letterboxBitmap = Bitmap.createBitmap(
                            INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
                    letterboxCanvas = new Canvas(letterboxBitmap);
                }
                letterboxCanvas.drawColor(PAD_COLOR);
                letterboxCanvas.drawBitmap(bitmap,
                        new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()),
                        new Rect(lb.padX, lb.padY,
                                lb.padX + lb.contentWidth, lb.padY + lb.contentHeight),
                        null);
                modelInput = letterboxBitmap;
            }

            bitmapToInputBuffer(modelInput);

            int numClasses = labels.size();
            float[][][] output = new float[1][4 + numClasses][MAX_DETECTIONS];

            if (isInt8) {
                byte[][][] rawOutput = new byte[1][4 + numClasses][MAX_DETECTIONS];
                tflite.run(inputBuffer, rawOutput);

                for (int row = 0; row < 4 + numClasses; row++) {
                    for (int col = 0; col < MAX_DETECTIONS; col++) {
                        output[0][row][col] =
                                (rawOutput[0][row][col] - outputZeroPoint) * outputScale;
                    }
                }
            } else {
                tflite.run(inputBuffer, output);
            }

            results = parseOutput(output[0], numClasses, lb);

        } catch (Exception e) {
            Log.e(TAG, "Inference error", e);
        }

        return results;
    }

    private List<Detection> parseOutput(float[][] output, int numClasses, Letterbox lb) {
        List<Detection> detections = new ArrayList<>();

        for (int i = 0; i < MAX_DETECTIONS; i++) {
            float maxScore = 0;
            int bestClass = -1;

            for (int c = 0; c < numClasses; c++) {
                float score = output[4 + c][i];
                if (score > maxScore) {
                    maxScore = score;
                    bestClass = c;
                }
            }

            if (maxScore < CONFIDENCE_THRESHOLD || bestClass < 0) continue;

            float cx = output[0][i];
            float cy = output[1][i];
            float w  = output[2][i];
            float h  = output[3][i];

            // Box in model-input (letterboxed) normalized coords, then
            // inverse-mapped to source-frame normalized coords (clamped).
            float[] box = new float[]{
                    cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f};
            lb.inverseMapNormalized(box);
            if (box[2] <= box[0] || box[3] <= box[1]) continue; // fully in padding

            String label = (bestClass < labels.size()) ?
                    labels.get(bestClass) : "unknown";
            detections.add(new Detection(
                    new RectF(box[0], box[1], box[2], box[3]), label, maxScore));
        }

        return applyNMS(detections);
    }

    private List<Detection> applyNMS(List<Detection> detections) {
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
        assignTrackIds(result);

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

    public void close() {
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

    /**
     * Associates detections with tracks via {@link BoxTracker}, entirely in
     * normalized [0,1] coordinates. (The old implementation compared
     * normalized centers against a 150-pixel threshold, which could never
     * reject a match — the root cause of the unstable track IDs.)
     */
    private void assignTrackIds(List<Detection> currentDetections) {
        long nowMs = System.currentTimeMillis();
        List<float[]> boxes = new ArrayList<>(currentDetections.size());
        for (Detection d : currentDetections) {
            boxes.add(new float[]{
                    d.boundingBox.left, d.boundingBox.top,
                    d.boundingBox.right, d.boundingBox.bottom});
        }

        List<BoxTracker.Assignment> assignments = tracker.assign(boxes, nowMs);
        for (int i = 0; i < currentDetections.size(); i++) {
            Detection d = currentDetections.get(i);
            BoxTracker.Assignment a = assignments.get(i);
            d.trackId = a.trackId;
            d.velocityX = a.vx;
            d.velocityY = a.vy;
            d.timestampMs = nowMs;
        }
    }

    /**
     * Full tracker reset: clears all tracks AND the ID counter, so the next
     * detection starts at trackId 1. (clearAllTracks() used to clear only the
     * uploaded-ID set, leaking tracker state across sessions — R3.)
     */
    public void reset() {
        tracker.reset();
    }

    /** Number of currently tracked objects (for metrics/overlay). */
    public int getActiveTrackCount() {
        return tracker.getActiveCount();
    }

    /** Total track IDs ever assigned in this session (for metrics/overlay). */
    public int getTotalTrackIdCount() {
        return tracker.getTotalCreated();
    }

    /** Whether the GPU delegate is active (for metrics). */
    public boolean isGpuActive() {
        return gpuDelegate != null;
    }
}
