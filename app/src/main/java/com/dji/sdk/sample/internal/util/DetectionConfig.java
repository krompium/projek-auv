package com.dji.sdk.sample.internal.util;

/**
 * Single source of truth for detection/upload thresholds (U7, R6/R8).
 * Branch configuration: feat/model-yolo26n (YOLO26n Cleaned Master).
 */
public final class DetectionConfig {

    private DetectionConfig() {
    }

    /**
     * Detector gate: minimum class score for a raw box to survive Stage-1 parsing.
     * Calibrated to YOLO26n max-F1 threshold (c* = 0.322 val / 0.331 test).
     */
    public static final float CONFIDENCE_THRESHOLD = 0.32f;

    /** NMS IoU threshold (standard YOLO export convention). */
    public static final float IOU_THRESHOLD = 0.45f;

    /**
     * Firebase upload gate: aligned with YOLO26n operating point so confirmed
     * detections in [0.34, 0.40) are not silently dropped from field-test logs.
     */
    public static final float UPLOAD_CONFIDENCE_THRESHOLD = 0.32f;

    /**
     * Geo-grid cell precision in decimal places for dedup keying (U10).
     * 4 decimals ~= 11 m cells, matching the web dashboard's KDE/MAUP grid.
     */
    public static final int GEO_GRID_DECIMALS = 4;

    /**
     * Second-stage ByteTrack confidence floor: low-score detections in
     * [LOW_CONFIDENCE_THRESHOLD, CONFIDENCE_THRESHOLD) never spawn new tracks,
     * but can keep an already-confirmed track alive during motion blur.
     */
    public static final float LOW_CONFIDENCE_THRESHOLD = 0.20f;

    /**
     * Minimum consecutive hits before a track is considered confirmed for
     * OverlayView rendering and Firebase/field-test counting (SORT gate).
     */
    public static final int MIN_TRACK_HITS = 2;
}