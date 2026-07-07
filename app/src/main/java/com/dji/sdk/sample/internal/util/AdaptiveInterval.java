package com.dji.sdk.sample.internal.util;

/**
 * Pure-math policy for the adaptive capture interval (U9, R9).
 *
 * <p>The fixed 400 ms interval ignored both the measured cycle time and the
 * scene: during long static hovers the Helio G99 heats up and inference
 * creeps. Policy: derive the interval from a rolling average of the real
 * cycle time (capture+infer) with headroom, clamped to a floor that keeps
 * Dropped=0, and stretch it when the scene is static (cheap luminance-grid
 * comparison), restoring immediately on motion.</p>
 */
public final class AdaptiveInterval {

    /** Never capture faster than this — protects Dropped=0 (safe floor). */
    public static final long FLOOR_MS = 300;
    /** Responsive-mode ceiling. */
    public static final long ACTIVE_CEIL_MS = 800;
    /** Static-scene ceiling (thermal relief during long hovers). */
    public static final long STATIC_CEIL_MS = 1200;
    /** Headroom over the average cycle so the pipe never saturates. */
    public static final float HEADROOM = 1.3f;
    /** EMA smoothing for the cycle-time average. */
    public static final float EMA_ALPHA = 0.3f;
    /** Consecutive near-identical frames before slowing down. */
    public static final int STATIC_STREAK_THRESHOLD = 5;
    /** Mean per-sample luminance delta below which frames count as identical. */
    public static final int STATIC_LUMA_DELTA = 6;

    private AdaptiveInterval() {
    }

    /** Exponential moving average of the measured cycle time (ms). */
    public static float updateEma(float emaMs, long measuredCycleMs) {
        return (1f - EMA_ALPHA) * emaMs + EMA_ALPHA * measuredCycleMs;
    }

    /**
     * Next capture interval: cycle-time average with headroom, clamped to
     * [FLOOR, ACTIVE_CEIL]; doubled (up to STATIC_CEIL) when static.
     */
    public static long compute(float emaCycleMs, boolean staticScene) {
        long base = (long) (emaCycleMs * HEADROOM);
        base = Math.max(FLOOR_MS, Math.min(ACTIVE_CEIL_MS, base));
        if (staticScene) {
            base = Math.min(STATIC_CEIL_MS, base * 2);
        }
        return base;
    }

    /**
     * Compares two luminance signatures (same length). True when the mean
     * absolute delta is below {@link #STATIC_LUMA_DELTA} — i.e. the scene
     * is effectively unchanged (static hover).
     */
    public static boolean isStatic(int[] previous, int[] current) {
        if (previous == null || current == null || previous.length != current.length
                || previous.length == 0) {
            return false;
        }
        long sum = 0;
        for (int i = 0; i < previous.length; i++) {
            sum += Math.abs(previous[i] - current[i]);
        }
        return (sum / previous.length) < STATIC_LUMA_DELTA;
    }
}
