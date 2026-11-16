package com.dji.sdk.sample.internal.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Geo-grid aggregation for Firebase dedup (U10, R10 / KTD5) enhanced with
 * Haversine Proximity Cell Anchoring (<= 6.0 m) and Spatio-Temporal Track-Set Union.
 *
 * <p>Pure Java — JVM-testable.</p>
 */
public class GeoGridAggregator {

    /** Haversine radius (meters) to prevent GPS boundary jitter from splitting a waste pile. */
    private static final double PROXIMITY_HYSTERESIS_METERS = 6.0;
    private static final double EARTH_RADIUS_METERS = 6371000.0;

    /** Result of a confirmed frame aggregation update on a geographic cell. */
    public static class CellSummary {
        public final String cellKey;
        public final int effectiveCount;
        public final int simultaneousPeak;
        public final int uniqueTrackCount;
        public final float avgConfidencePct;

        public CellSummary(String cellKey, int effectiveCount, int simultaneousPeak,
                           int uniqueTrackCount, float avgConfidencePct) {
            this.cellKey = cellKey;
            this.effectiveCount = effectiveCount;
            this.simultaneousPeak = simultaneousPeak;
            this.uniqueTrackCount = uniqueTrackCount;
            this.avgConfidencePct = avgConfidencePct;
        }
    }

    private final Map<String, Integer> cellPeaks = new HashMap<>();
    private final Map<String, Integer> cellSimultaneousPeaks = new HashMap<>();
    private final Map<String, Set<Integer>> cellUniqueTracks = new HashMap<>();
    private final Map<String, double[]> cellAnchorLatLng = new HashMap<>();
    private final Map<Integer, String> trackHomeCell = new HashMap<>();
    private final Map<Integer, Float> trackPeakConfidence = new HashMap<>();
    private final String format;

    private String lastActiveCellKey = null;

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
     * Original method preserved for 100% unit-test backward compatibility.
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

    /**
     * Records confirmed detections for a frame at {@code (lat, lng)} using
     * Haversine Proximity Hysteresis (<= 6 m) + Spatio-Temporal Track-Set Union.
     */
    public synchronized CellSummary recordConfirmedDetections(
            double lat, double lng, int[] confirmedIds, float[] confidences, int count) {
        if (Double.isNaN(lat) || Double.isNaN(lng)) return null;
        if (lat == 0 && lng == 0) return null;
        if (count <= 0 || confirmedIds == null || confidences == null) return null;

        // 1. Resolve cell key with Haversine proximity hysteresis (<= 6.0 m)
        //    so GPS jitter across a .4f grid boundary doesn't split one pile into two cells.
        String targetCell = resolveAnchoredCell(lat, lng, confirmedIds, count);
        lastActiveCellKey = targetCell;
        if (!cellAnchorLatLng.containsKey(targetCell)) {
            cellAnchorLatLng.put(targetCell, new double[]{lat, lng});
        }

        Set<Integer> cellSet = cellUniqueTracks.get(targetCell);
        if (cellSet == null) {
            cellSet = new HashSet<>();
            cellUniqueTracks.put(targetCell, cellSet);
        }

        int inCellSimultaneous = 0;
        for (int i = 0; i < count; i++) {
            int tid = confirmedIds[i];
            float conf = confidences[i];
            if (tid <= 0) continue;

            Float prevBestConf = trackPeakConfidence.get(tid);
            if (prevBestConf == null || conf > prevBestConf) {
                trackPeakConfidence.put(tid, conf);
            }

            String home = trackHomeCell.get(tid);
            if (home == null) {
                trackHomeCell.put(tid, targetCell);
                home = targetCell;
            }
            if (targetCell.equals(home)) {
                cellSet.add(tid);
                inCellSimultaneous++;
            }
        }

        if (inCellSimultaneous <= 0 && cellSet.isEmpty()) return null;

        Integer prevSimPeak = cellSimultaneousPeaks.get(targetCell);
        int simPeak = (prevSimPeak == null)
                ? inCellSimultaneous : Math.max(prevSimPeak, inCellSimultaneous);
        cellSimultaneousPeaks.put(targetCell, simPeak);

        int uniqueCount = cellSet.size();
        int effectiveCount = Math.max(simPeak, uniqueCount);

        Integer prevEffective = cellPeaks.get(targetCell);
        if (prevEffective == null || effectiveCount > prevEffective) {
            cellPeaks.put(targetCell, effectiveCount);
            float avgConfPct = computeCellAvgConfidencePct(cellSet);
            return new CellSummary(
                    targetCell, effectiveCount, simPeak, uniqueCount, avgConfPct);
        }
        return null;
    }

    private String resolveAnchoredCell(double lat, double lng, int[] confirmedIds, int count) {
        // First check if any visible track already belongs to an existing cell within 6 m
        for (int i = 0; i < count; i++) {
            String home = trackHomeCell.get(confirmedIds[i]);
            if (home != null) {
                double[] anchor = cellAnchorLatLng.get(home);
                if (anchor != null && haversineMeters(lat, lng, anchor[0], anchor[1])
                        <= PROXIMITY_HYSTERESIS_METERS) {
                    return home;
                }
            }
        }
        // Next check if the drone is still within 6 m of the last active cell's anchor
        if (lastActiveCellKey != null) {
            double[] anchor = cellAnchorLatLng.get(lastActiveCellKey);
            if (anchor != null && haversineMeters(lat, lng, anchor[0], anchor[1])
                    <= PROXIMITY_HYSTERESIS_METERS) {
                return lastActiveCellKey;
            }
        }
        return cellKey(lat, lng);
    }

    public static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat * 0.5) * Math.sin(dLat * 0.5)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng * 0.5) * Math.sin(dLng * 0.5);
        return 2.0 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    private float computeCellAvgConfidencePct(Set<Integer> trackIds) {
        if (trackIds == null || trackIds.isEmpty()) return 0f;
        float sum = 0f;
        int n = 0;
        for (Integer tid : trackIds) {
            Float c = trackPeakConfidence.get(tid);
            if (c != null) {
                sum += c;
                n++;
            }
        }
        return (n > 0) ? (sum / n) * 100f : 0f;
    }

    /** Current peak for a cell, or 0 if the cell has never been recorded. */
    public synchronized int getPeak(double lat, double lng) {
        Integer v = cellPeaks.get(cellKey(lat, lng));
        return v == null ? 0 : v;
    }

    public synchronized int getCellCount() {
        return cellPeaks.size();
    }

    /** Total globally unique confirmed track IDs seen since last reset. */
    public synchronized int getGlobalUniqueTrackCount() {
        return trackHomeCell.size();
    }

    /** Mean peak confidence (%) across all globally unique confirmed tracks. */
    public synchronized float getGlobalAvgConfidencePct() {
        if (trackPeakConfidence.isEmpty()) return 0f;
        float sum = 0f;
        for (Float c : trackPeakConfidence.values()) sum += c;
        return (sum / trackPeakConfidence.size()) * 100f;
    }

    /** Session reset (paired with the tracker reset in clearAllTracks). */
    public synchronized void reset() {
        cellPeaks.clear();
        cellSimultaneousPeaks.clear();
        cellUniqueTracks.clear();
        cellAnchorLatLng.clear();
        trackHomeCell.clear();
        trackPeakConfidence.clear();
        lastActiveCellKey = null;
    }
}