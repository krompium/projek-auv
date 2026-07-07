package com.dji.sdk.sample.internal.util;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Rolling CSV metrics logger for the detection pipeline.
 *
 * File-based by design: the phone's USB port is occupied by the RC during
 * flight, so Logcat is impractical. All diagnostics land in
 * {@code <externalFilesDir>/metrics/detection_metrics.csv} and can be pulled
 * after a run with any file manager.
 *
 * Contract:
 *  - Writes are synchronized and expected to be called from the background
 *    processing thread (never the UI thread).
 *  - If the target directory is unusable the logger degrades to a no-op and
 *    never throws — diagnostics must not crash the pipeline.
 *  - The file rotates by size: when it exceeds {@code maxBytes} it is renamed
 *    to {@code detection_metrics.1.csv} (replacing any previous rotation), so
 *    disk usage is bounded at ~2x maxBytes.
 */
public class MetricsLogger {

    public static final String HEADER =
            "timestampMs,captureMs,inferMs,droppedCount,detectionCount,activeTrackCount,totalTrackIds,gpuActive,intervalMs";

    private static final long DEFAULT_MAX_BYTES = 512 * 1024;

    private final File logFile;
    private final File rotatedFile;
    private final File eventFile;
    private final long maxBytes;
    private boolean disabled = false;

    public MetricsLogger(File directory) {
        this(directory, DEFAULT_MAX_BYTES);
    }

    public MetricsLogger(File directory, long maxBytes) {
        this.maxBytes = maxBytes;
        File log = null, rotated = null, events = null;
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
    }

    /** One row per processed frame. Call from the processing thread. */
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
            disabled = true; // diagnostics must never take the pipeline down
        }
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
}
