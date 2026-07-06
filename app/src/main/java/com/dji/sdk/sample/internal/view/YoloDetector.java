package com.dji.sdk.sample.internal.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

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
    private int nextTrackId = 1;
    private List<TrackedObject> trackedObjects = new ArrayList<>();
    private static final int MAX_DETECTIONS = 8400;
    private static final float CONFIDENCE_THRESHOLD = 0.35f;
    private static final float IOU_THRESHOLD = 0.45f;

    private Interpreter tflite;
    private List<String> labels = new ArrayList<>();
    private ByteBuffer inputBuffer;

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

        public Detection(RectF boundingBox, String label, float confidence) {
            this.boundingBox = boundingBox;
            this.label = label;
            this.confidence = confidence;
        }
    }

    private static class TrackedObject {
        int id;
        RectF rect;
        int lostFrames;

        public TrackedObject(int id, RectF rect) {
            this.id = id;
            this.rect = rect;
            this.lostFrames = 0;
        }
    }

    public YoloDetector(Context context) {
        try {
            loadLabels(context);
            MappedByteBuffer modelBuffer = loadModelFile(context);

            Interpreter.Options options = new Interpreter.Options();

            CompatibilityList compatList = new CompatibilityList();
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

            tflite = new Interpreter(modelBuffer, options);

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
                inputBuffer.put((byte) (r + inputZeroPoint));
                inputBuffer.put((byte) (g + inputZeroPoint));
                inputBuffer.put((byte) (b + inputZeroPoint));
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
            bitmapToInputBuffer(bitmap);

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

            results = parseOutput(output[0], numClasses);

        } catch (Exception e) {
            Log.e(TAG, "Inference error", e);
        }

        return results;
    }

    private List<Detection> parseOutput(float[][] output, int numClasses) {
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

            float left   = Math.max(0f, Math.min(1f, cx - w / 2f));
            float top    = Math.max(0f, Math.min(1f, cy - h / 2f));
            float right  = Math.max(0f, Math.min(1f, cx + w / 2f));
            float bottom = Math.max(0f, Math.min(1f, cy + h / 2f));

            String label = (bestClass < labels.size()) ?
                    labels.get(bestClass) : "unknown";
            detections.add(new Detection(
                    new RectF(left, top, right, bottom), label, maxScore));
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
    }

    private void assignTrackIds(List<Detection> currentDetections) {
        float MAX_DISTANCE = 150.0f;
        int MAX_LOST_FRAMES = 5;

        if (trackedObjects.isEmpty()) {
            for (Detection d : currentDetections) {
                d.trackId = nextTrackId++;
                trackedObjects.add(new TrackedObject(d.trackId, d.boundingBox));
            }
            return;
        }

        boolean[] matchedTracks = new boolean[trackedObjects.size()];

        for (Detection d : currentDetections) {
            float centerX = d.boundingBox.centerX();
            float centerY = d.boundingBox.centerY();

            int bestTrackIdx = -1;
            float minDistance = MAX_DISTANCE;

            for (int j = 0; j < trackedObjects.size(); j++) {
                if (matchedTracks[j]) continue;
                TrackedObject t = trackedObjects.get(j);
                float dist = (float) Math.hypot(centerX - t.rect.centerX(), centerY - t.rect.centerY());

                if (dist < minDistance) {
                    minDistance = dist;
                    bestTrackIdx = j;
                }
            }

            if (bestTrackIdx != -1) {
                matchedTracks[bestTrackIdx] = true;
                d.trackId = trackedObjects.get(bestTrackIdx).id;
                trackedObjects.get(bestTrackIdx).rect = d.boundingBox;
                trackedObjects.get(bestTrackIdx).lostFrames = 0;
            } else {
                d.trackId = nextTrackId++;
                trackedObjects.add(new TrackedObject(d.trackId, d.boundingBox));
            }
        }

        for (int j = trackedObjects.size() - 1; j >= 0; j--) {
            if (!matchedTracks[j]) {
                trackedObjects.get(j).lostFrames++;
                if (trackedObjects.get(j).lostFrames > MAX_LOST_FRAMES) {
                    trackedObjects.remove(j);
                }
            }
        }
    }
}