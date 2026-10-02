package com.dji.sdk.sample.internal.util;

/**
 * Single source of truth for detection/upload thresholds (U7, R6/R8).
 *
 * <p>Previously the detector gate (0.35) and the Firebase upload gate (0.40)
 * lived in different files with no documented relationship. They are now
 * defined together so the operating point is chosen deliberately.</p>
 *
 * <p><b>How to (re)calibrate:</b> run the U6 harness on the Roboflow test
 * split, then {@code tools/parity_check.py --labels <gt> --sweep 0.20:0.60:0.05}
 * and set {@link #CONFIDENCE_THRESHOLD} near the max-F1 threshold, keeping
 * {@link #UPLOAD_CONFIDENCE_THRESHOLD} at or slightly above it. Record the
 * chosen values and the sweep table in docs/field-test-protocol.md.</p>
 */
public final class DetectionConfig {

    private DetectionConfig() {
    }

    /**
     * Detector gate: minimum class score for a raw box to survive parsing.
     * Current value is the historical default; replace with the max-F1
     * threshold from the U6/parity_check sweep once the harness has run on
     * the real test split.
     */
    public static final float CONFIDENCE_THRESHOLD = 0.38f;

    /** NMS IoU threshold (unchanged; standard YOLOv8 export convention). */
    public static final float IOU_THRESHOLD = 0.45f;

    /**
     * Firebase upload gate: minimum confidence for a detection to reach
     * Firestore. Kept >= CONFIDENCE_THRESHOLD so uploads are a strict subset
     * of what the pilot sees on the overlay.
     */
    public static final float UPLOAD_CONFIDENCE_THRESHOLD = 0.40f;

    /**
     * Geo-grid cell precision in decimal places for dedup keying (U10).
     * 4 decimals ~= 11 m cells, matching the web dashboard's KDE/MAUP grid.
     */
    public static final int GEO_GRID_DECIMALS = 4;
}
