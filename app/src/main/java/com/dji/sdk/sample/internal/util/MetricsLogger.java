package com.dji.sdk.sample.internal.util;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Rolling CSV metrics logger for the detection pipeline plus automated
 * Field-Test Summary & Telemetry loggers for Laporan Akhir tables (Tabel 2, 3, 6).
 */
public class MetricsLogger {

    public static final String HEADER =
            "timestampMs,captureMs,inferMs,droppedCount,detectionCount,activeTrackCount,totalTrackIds,gpuActive,intervalMs";

    public static final String FIELD_TELEMETRY_HEADER =
            "timestampMs,scenario,captureMs,inferMs,droppedCount,rawDetCount,confirmedDetCount,activeTrackCount,totalTrackIds,meanConfPct,latitude,longitude,altitudeM,speedMs,satellites";

    public static final String FIELD_SUMMARY_HEADER =
            "timestampIso,scenario,conditionNotes,targetAltM,avgMeasuredAltM,maxSpeedMs,avgSpeedMs,groundTruth,peakSimultaneousCount,uniqueConfirmedTracks,detectedCount,diff,accuracyPct,avgConfidencePct,meanCaptureMs,meanInferMs,droppedFrames,durationSec";

    private static final long DEFAULT_MAX_BYTES = 512 * 1024;

    private final File logFile;
    private final File rotatedFile;
    private final File eventFile;
    private final File fieldTelemetryFile;
    private final File fieldSummaryFile;
    private final long maxBytes;
    private boolean disabled = false;

    public MetricsLogger(File directory) {
        this(directory, DEFAULT_MAX_BYTES);
    }

    public MetricsLogger(File directory, long maxBytes) {
        this.maxBytes = maxBytes;
        File log = null, rotated = null, events = null, fTelem = null, fSummary = null;
        try {
            if (directory != null) {
                if (!directory.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    directory.mkdirs();
                }
                if (directory.isDirectory() && directory.canWrite()) {
                    log = new File(directory, "detection_metrics.csv");
                    rotated = new File(directory, "detection_metrics.1.csv");
                    events = new File(directory, "detection_events.log");
                    fTelem = new File(directory, "field_test_telemetry.csv");
                    fSummary = new File(directory, "field_test_summary.csv");
                } else {
                    disabled = true;
                }
            } else {
                disabled = true;
            }
        } catch (Exception e) {
            disabled = true;
        }
        this.logFile = log;
        this.rotatedFile = rotated;
        this.eventFile = events;
        this.fieldTelemetryFile = fTelem;
        this.fieldSummaryFile = fSummary;
    }

    /** One row per processed frame (original 9-column contract preserved for unit tests). */
    public synchronized void logFrame(long timestampMs, long captureMs, long inferMs,
                                      int droppedCount, int detectionCount,
                                      int activeTrackCount, int totalTrackIds,
                                      boolean gpuActive, long intervalMs) {
        if (disabled) return;
        try {
            rotateIfNeeded();
            boolean writeHeader = !logFile.exists() || logFile.length() == 0;
            try (FileWriter w = new FileWriter(logFile, true)) {
                if (writeHeader) {
                    w.write(HEADER);
                    w.write('\n');
                }
                w.write(timestampMs + "," + captureMs + "," + inferMs + ","
                        + droppedCount + "," + detectionCount + ","
                        + activeTrackCount + "," + totalTrackIds + ","
                        + (gpuActive ? 1 : 0) + "," + intervalMs + "\n");
            }
        } catch (IOException e) {
            disabled = true;
        }
    }

    /**
     * Extended per-frame telemetry logger linking inference metrics with UAV flight
     * kinematics (altitude, horizontal speed m/s, GPS satellites, active scenario).
     */
    public synchronized void logFieldTelemetry(
            long timestampMs, String scenario, long captureMs, long inferMs,
            int droppedCount, int rawDetCount, int confirmedDetCount,
            int activeTrackCount, int totalTrackIds, float meanConfPct,
            double lat, double lng, float altitudeM, float speedMs, int satellites) {
        if (disabled || fieldTelemetryFile == null) return;
        try {
            boolean writeHeader = !fieldTelemetryFile.exists() || fieldTelemetryFile.length() == 0;
            try (FileWriter w = new FileWriter(fieldTelemetryFile, true)) {
                if (writeHeader) {
                    w.write(FIELD_TELEMETRY_HEADER);
                    w.write('\n');
                }
                String cleanScenario = sanitizeCsv(scenario);
                w.write(String.format(Locale.US,
                        "%d,%s,%d,%d,%d,%d,%d,%d,%d,%.1f,%.6f,%.6f,%.2f,%.2f,%d\n",
                        timestampMs, cleanScenario, captureMs, inferMs, droppedCount,
                        rawDetCount, confirmedDetCount, activeTrackCount, totalTrackIds,
                        meanConfPct, lat, lng, altitudeM, speedMs, satellites));
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Appends one completed test-session row to {@code field_test_summary.csv},
     * directly formatted for Tabel 3 & Tabel 6 of Laporan Akhir.
     */
    public synchronized void logFieldTestSummary(
            String scenario, String conditionNotes, float targetAltM, float avgMeasuredAltM,
            float maxSpeedMs, float avgSpeedMs, int groundTruth,
            int peakSimultaneousCount, int uniqueConfirmedTracks, int detectedCount,
            int diff, float accuracyPct, float avgConfidencePct,
            float meanCaptureMs, float meanInferMs, int droppedFrames, float durationSec) {
        if (disabled || fieldSummaryFile == null) return;
        try {
            boolean writeHeader = !fieldSummaryFile.exists() || fieldSummaryFile.length() == 0;
            String isoNow = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date());
            try (FileWriter w = new FileWriter(fieldSummaryFile, true)) {
                if (writeHeader) {
                    w.write(FIELD_SUMMARY_HEADER);
                    w.write('\n');
                }
                w.write(String.format(Locale.US,
                        "%s,%s,%s,%.1f,%.2f,%.2f,%.2f,%d,%d,%d,%d,%d,%.1f,%.1f,%.1f,%.1f,%d,%.1f\n",
                        isoNow,
                        sanitizeCsv(scenario),
                        sanitizeCsv(conditionNotes),
                        targetAltM, avgMeasuredAltM, maxSpeedMs, avgSpeedMs,
                        groundTruth, peakSimultaneousCount, uniqueConfirmedTracks,
                        detectedCount, diff, accuracyPct, avgConfidencePct,
                        meanCaptureMs, meanInferMs, droppedFrames, durationSec));
            }
        } catch (IOException ignored) {
        }
    }

    private static String sanitizeCsv(String s) {
        if (s == null || s.trim().isEmpty()) return "-";
        return s.replace(',', ';').replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** Free-form event line (model load errors, resets, mode switches). */
    public synchronized void logEvent(String tag, String message) {
        if (disabled || eventFile == null) return;
        try {
            try (FileWriter w = new FileWriter(eventFile, true)) {
                w.write(System.currentTimeMillis() + " [" + tag + "] "
                        + (message == null ? "" : message.replace('\n', ' ')) + "\n");
            }
        } catch (IOException e) {
            disabled = true;
        }
    }

    private void rotateIfNeeded() {
        if (logFile.exists() && logFile.length() > maxBytes) {
            if (rotatedFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                rotatedFile.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            logFile.renameTo(rotatedFile);
        }
    }

    public synchronized boolean isEnabled() {
        return !disabled;
    }

    public File getLogFile() {
        return logFile;
    }

    public File getRotatedFile() {
        return rotatedFile;
    }

    public File getFieldSummaryFile() {
        return fieldSummaryFile;
    }
}