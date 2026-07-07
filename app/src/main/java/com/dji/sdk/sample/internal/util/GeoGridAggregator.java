package com.dji.sdk.sample.internal.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Geo-grid peak aggregation for Firebase dedup (U10, R10 / KTD5).
 *
 * <p>Upload dedup used to key on {@code trackId} alone, which the tracking
 * bug made unreliable: ID churn caused over-counting, false merges caused
 * under-counting. The source of truth is now a geographic grid cell
 * (~11 m via 4-decimal rounding, matching the web dashboard's KDE/MAUP
 * grid). Each cell stores the PEAK number of simultaneous detections seen
 * there; Firestore is written only when a cell's peak increases, so a
 * stationary pile filmed for 10 s produces one aggregated record, not one
 * per detection cycle.</p>
 *
 * <p>Pure Java — JVM-testable.</p>
 */
public class GeoGridAggregator {

    private final Map<String, Integer> cellPeaks = new HashMap<>();
    private final String format;

    public GeoGridAggregator() {
        this(DetectionConfig.GEO_GRID_DECIMALS);
    }

    public GeoGridAggregator(int decimals) {
        this.format = "%." + decimals + "f,%." + decimals + "f";
    }

    /** Stable cell key, e.g. {@code "-6.2001,106.8167"} (Locale.US). */
    public String cellKey(double lat, double lng) {
        return String.format(Locale.US, format, lat, lng);
    }

    /**
     * Records {@code simultaneousCount} detections at a location.
     *
     * @return the new peak when it increased (a Firestore write is due),
     *         or {@code -1} when nothing changed (no write) or the fix is
     *         invalid (NaN / 0,0 guard preserved).
     */
    public synchronized int recordCount(double lat, double lng, int simultaneousCount) {
        if (Double.isNaN(lat) || Double.isNaN(lng)) return -1;
        if (lat == 0 && lng == 0) return -1;
        if (simultaneousCount <= 0) return -1;

        String key = cellKey(lat, lng);
        Integer prev = cellPeaks.get(key);
        if (prev == null || simultaneousCount > prev) {
            cellPeaks.put(key, simultaneousCount);
            return simultaneousCount;
        }
        return -1;
    }

    /** Current peak for a cell, or 0 if the cell has never been recorded. */
    public synchronized int getPeak(double lat, double lng) {
        Integer v = cellPeaks.get(cellKey(lat, lng));
        return v == null ? 0 : v;
    }

    public synchronized int getCellCount() {
        return cellPeaks.size();
    }

    /** Session reset (paired with the tracker reset in clearAllTracks). */
    public synchronized void reset() {
        cellPeaks.clear();
    }
}
