package com.dji.sdk.sample.internal.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.dji.sdk.sample.internal.util.MetricsLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.List;

/**
 * Offline parity & accuracy harness (U6, R5/R6).
 *
 * <p>Replays a folder of test images (e.g. the Roboflow test split) through
 * the exact same {@link YoloDetector#detect} path used in flight — letterbox,
 * TFLite, parse, NMS — and dumps one JSONL row per image. The dump is then
 * compared against Colab/PC reference inference by
 * {@code tools/parity_check.py} to prove there is no systematic parsing or
 * coordinate error, and to compute precision/recall for threshold tuning.</p>
 *
 * <p>Usage (no Logcat needed — everything is file-based):</p>
 * <ol>
 *   <li>Push test images to {@code <externalFilesDir>/harness_images/}</li>
 *   <li>Create an empty marker file {@code <externalFilesDir>/run_harness}</li>
 *   <li>Open the FPV view; after the model loads, the harness runs once and
 *       writes {@code <externalFilesDir>/harness_predictions.jsonl}</li>
 *   <li>Pull the JSONL and run {@code tools/parity_check.py} against it</li>
 * </ol>
 */
public final class DetectionHarness {

    private static final String TAG = "DetectionHarness";
    private static final String TRIGGER_FILE = "run_harness";
    private static final String IMAGES_DIR = "harness_images";
    private static final String OUTPUT_FILE = "harness_predictions.jsonl";

    private DetectionHarness() {
    }

    /** One-shot trigger: runs only when the marker file exists, then deletes it. */
    public static void runIfRequested(Context context, YoloDetector detector,
                                      MetricsLogger logger) {
        try {
            File base = context.getExternalFilesDir(null);
            if (base == null || detector == null) return;
            File trigger = new File(base, TRIGGER_FILE);
            if (!trigger.exists()) return;

            File imagesDir = new File(base, IMAGES_DIR);
            File outFile = new File(base, OUTPUT_FILE);
            int processed = run(detector, imagesDir, outFile, logger);

            //noinspection ResultOfMethodCallIgnored
            trigger.delete();
            if (logger != null) {
                logger.logEvent(TAG, "harness done, images=" + processed
                        + ", output=" + outFile.getName());
            }
        } catch (Exception e) {
            Log.e(TAG, "Harness failed", e);
            if (logger != null) logger.logEvent(TAG, "harness FAILED: " + e.getMessage());
        }
    }

    /**
     * Replays every image in {@code imagesDir} through the detector and
     * writes one JSONL row per image. The tracker is reset per image so
     * replay order cannot leak track state between images.
     *
     * @return number of images processed
     */
    public static int run(YoloDetector detector, File imagesDir, File outFile,
                          MetricsLogger logger) throws Exception {
        File[] files = imagesDir.listFiles((dir, name) -> {
            String n = name.toLowerCase();
            return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png");
        });
        if (files == null || files.length == 0) {
            if (logger != null) logger.logEvent(TAG, "no images in " + imagesDir);
            return 0;
        }
        Arrays.sort(files);

        int processed = 0;
        try (FileWriter w = new FileWriter(outFile, false)) {
            for (File img : files) {
                Bitmap bmp = BitmapFactory.decodeFile(img.getAbsolutePath());
                if (bmp == null) continue;

                detector.reset(); // no cross-image track leakage
                long t0 = System.currentTimeMillis();
                List<YoloDetector.Detection> dets = detector.detect(bmp);
                long inferMs = System.currentTimeMillis() - t0;
                bmp.recycle();

                JSONObject row = new JSONObject();
                row.put("image", img.getName());
                row.put("inferMs", inferMs);
                JSONArray arr = new JSONArray();
                for (YoloDetector.Detection d : dets) {
                    JSONObject o = new JSONObject();
                    JSONArray box = new JSONArray();
                    box.put(d.boundingBox.left);
                    box.put(d.boundingBox.top);
                    box.put(d.boundingBox.right);
                    box.put(d.boundingBox.bottom);
                    o.put("box", box);            // normalized ltrb on ORIGINAL image
                    o.put("label", d.label);
                    o.put("confidence", d.confidence);
                    arr.put(o);
                }
                row.put("detections", arr);
                w.write(row.toString());
                w.write('\n');
                processed++;
            }
        }
        Log.d(TAG, "Harness processed " + processed + " images -> " + outFile);
        return processed;
    }
}
