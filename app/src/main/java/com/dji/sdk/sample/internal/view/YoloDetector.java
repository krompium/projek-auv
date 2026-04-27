package com.dji.sdk.sample.internal.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
//import org.tensorflow.lite.gpu.GpuDelegate;

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
    private static final String MODEL_FILE = "yolov8n.tflite";
    private static final String LABELS_FILE = "labels.txt";

    private static final int INPUT_SIZE = 640;
    private static final int MAX_DETECTIONS = 8400;
    private static final float CONFIDENCE_THRESHOLD = 0.35f;
    private static final float IOU_THRESHOLD = 0.45f;

    private Interpreter tflite;
//    private GpuDelegate gpuDelegate;
    private List<String> labels = new ArrayList<>();
    private ByteBuffer inputBuffer;

    public static class Detection {
        public final RectF boundingBox;
        public final String label;
        public final float confidence;

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
            options.setNumThreads(4);
            Log.d(TAG, "Using CPU with 4 threads");

            tflite = new Interpreter(modelBuffer, options);

            // Pre-allocate input buffer: 1 x 640 x 640 x 3 x 4 bytes (float32)
            inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4);
            inputBuffer.order(ByteOrder.nativeOrder());

            Log.d(TAG, "YoloDetector initialized successfully");

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize YoloDetector", e);
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
        Log.d(TAG, "Loaded " + labels.size() + " labels");
    }

    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        android.content.res.AssetFileDescriptor fileDescriptor =
                context.getAssets().openFd(MODEL_FILE);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
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
            // Normalize RGB to [0, 1]
            inputBuffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f); // R
            inputBuffer.putFloat(((pixel >> 8)  & 0xFF) / 255.0f); // G
            inputBuffer.putFloat(( pixel        & 0xFF) / 255.0f); // B
        }
    }

    public List<Detection> detect(Bitmap bitmap) {
        List<Detection> results = new ArrayList<>();
        if (tflite == null || bitmap == null) return results;

        try {
            bitmapToInputBuffer(bitmap);

            // YOLOv8n TFLite output shape: [1, 84, 8400]
            // 84 = 4 (box: cx,cy,w,h) + 80 (class scores)
            float[][][] output = new float[1][84][MAX_DETECTIONS];
            tflite.run(inputBuffer, output);

            results = parseOutput(output[0]);

        } catch (Exception e) {
            Log.e(TAG, "Inference error", e);
        }

        return results;
    }

    private List<Detection> parseOutput(float[][] output) {
        List<Detection> detections = new ArrayList<>();

        for (int i = 0; i < MAX_DETECTIONS; i++) {
            // Find best class score for this detection
            float maxScore = 0;
            int bestClass = -1;

            for (int c = 0; c < labels.size(); c++) {
                float score = output[4 + c][i];
                if (score > maxScore) {
                    maxScore = score;
                    bestClass = c;
                }
            }

            if (maxScore < CONFIDENCE_THRESHOLD || bestClass < 0) continue;

            // Box is in cx, cy, w, h format normalized to 640
            float cx = output[0][i];
            float cy = output[1][i];
            float w  = output[2][i];
            float h  = output[3][i];

            float left   = cx - w / 2f;
            float top    = cy - h / 2f;
            float right  = cx + w / 2f;
            float bottom = cy + h / 2f;

            // Clamp to [0,1]
            left   = Math.max(0f, Math.min(1f, left));
            top    = Math.max(0f, Math.min(1f, top));
            right  = Math.max(0f, Math.min(1f, right));
            bottom = Math.max(0f, Math.min(1f, bottom));

            String label = (bestClass < labels.size()) ? labels.get(bestClass) : "unknown";
            detections.add(new Detection(new RectF(left, top, right, bottom), label, maxScore));
        }

        return applyNMS(detections);
    }

    private List<Detection> applyNMS(List<Detection> detections) {
        // Sort by confidence descending
        detections.sort((a, b) -> Float.compare(b.confidence, a.confidence));

        List<Detection> result = new ArrayList<>();
        boolean[] suppressed = new boolean[detections.size()];

        for (int i = 0; i < detections.size(); i++) {
            if (suppressed[i]) continue;
            result.add(detections.get(i));
            for (int j = i + 1; j < detections.size(); j++) {
                if (!suppressed[j]) {
                    float iou = computeIoU(detections.get(i).boundingBox,
                            detections.get(j).boundingBox);
                    if (iou > IOU_THRESHOLD) suppressed[j] = true;
                }
            }
        }
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
//        if (gpuDelegate != null) {
//            gpuDelegate.close();
//            gpuDelegate = null;
//        }
    }
}