package com.dji.sdk.sample.internal.view;

/**
 * Pure-math inter-frame box prediction (U8, R7).
 *
 * <p>Inference runs every ~400 ms (the Helio G99 is at its ceiling — KTD4),
 * but the FPV renders at 30 FPS, so boxes used to freeze then jump. Between
 * detection cycles the overlay advances each box linearly along its track
 * velocity (estimated by {@link BoxTracker}), snapping back to the measured
 * box whenever a fresh detection arrives (dt resets to ~0, so the predicted
 * box equals the measured one — no drift accumulation).</p>
 *
 * <p>JVM-testable: no Android dependencies.</p>
 */
public final class BoxInterpolator {

    /**
     * Maximum extrapolation window. Beyond this a track is considered stale
     * and must no longer be drawn (prevents ghost boxes when an object
     * leaves the frame between cycles).
     */
    public static final long MAX_EXTRAPOLATION_MS = 700;

    private BoxInterpolator() {
    }

    /** True when a detection is too old to keep drawing (ghost removal). */
    public static boolean isExpired(long dtMs) {
        return dtMs > MAX_EXTRAPOLATION_MS;
    }

    /**
     * Advances a normalized box by its velocity over {@code dtMs}
     * (capped at {@link #MAX_EXTRAPOLATION_MS}), keeping the box inside
     * [0,1] without shrinking it.
     *
     * @param vx,vy velocity in normalized units per second
     * @return predicted {left, top, right, bottom}
     */
    public static float[] predict(float left, float top, float right, float bottom,
                                  float vx, float vy, long dtMs) {
        long dt = Math.max(0, Math.min(dtMs, MAX_EXTRAPOLATION_MS));
        float dx = vx * dt / 1000f;
        float dy = vy * dt / 1000f;
        float[] out = {left + dx, top + dy, right + dx, bottom + dy};

        float w = out[2] - out[0];
        float h = out[3] - out[1];
        if (out[0] < 0f) { out[0] = 0f; out[2] = w; }
        if (out[1] < 0f) { out[1] = 0f; out[3] = h; }
        if (out[2] > 1f) { out[2] = 1f; out[0] = 1f - w; }
        if (out[3] > 1f) { out[3] = 1f; out[1] = 1f - h; }
        return out;
    }
}
