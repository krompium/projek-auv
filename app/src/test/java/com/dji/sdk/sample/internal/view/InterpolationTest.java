package com.dji.sdk.sample.internal.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Covers R7 — smooth inter-frame box prediction. */
public class InterpolationTest {

    @Test
    public void constantVelocity_predictedWithinErrorAtMidCycle() {
        // Track at (0.4..0.5) moving +0.10 units/s in x; mid-cycle = 200 ms
        float[] p = BoxInterpolator.predict(0.40f, 0.40f, 0.50f, 0.50f,
                0.10f, 0f, 200);
        assertEquals(0.42f, p[0], 1e-4);
        assertEquals(0.52f, p[2], 1e-4);
        assertEquals(0.40f, p[1], 1e-4); // no y motion
        assertEquals("width preserved", 0.10f, p[2] - p[0], 1e-4);
    }

    @Test
    public void freshDetection_snapsToMeasuredBox() {
        // dt = 0 (a new detection just arrived) => predicted == measured
        float[] p = BoxInterpolator.predict(0.30f, 0.30f, 0.45f, 0.45f,
                0.50f, -0.50f, 0);
        assertEquals(0.30f, p[0], 0f);
        assertEquals(0.30f, p[1], 0f);
        assertEquals(0.45f, p[2], 0f);
        assertEquals(0.45f, p[3], 0f);
    }

    @Test
    public void staleTrack_expiresWithinOneCycle() {
        // One detection cycle is ~400 ms; a track that misses two cycles
        // (800 ms) must be flagged expired so no ghost box is drawn.
        assertFalse(BoxInterpolator.isExpired(400));
        assertFalse(BoxInterpolator.isExpired(BoxInterpolator.MAX_EXTRAPOLATION_MS));
        assertTrue(BoxInterpolator.isExpired(BoxInterpolator.MAX_EXTRAPOLATION_MS + 1));
        assertTrue(BoxInterpolator.isExpired(800));
    }

    @Test
    public void extrapolation_cappedBeyondWindow() {
        // dt far beyond the window must not run away: capped at 700 ms
        float[] capped = BoxInterpolator.predict(0.4f, 0.4f, 0.5f, 0.5f,
                0.10f, 0f, 10_000);
        float[] atCap = BoxInterpolator.predict(0.4f, 0.4f, 0.5f, 0.5f,
                0.10f, 0f, BoxInterpolator.MAX_EXTRAPOLATION_MS);
        assertEquals(atCap[0], capped[0], 0f);
        assertEquals(atCap[2], capped[2], 0f);
    }

    @Test
    public void prediction_staysInsideUnitBox_withoutShrinking() {
        // Fast track near the right edge: box must clamp, not shrink or escape
        float[] p = BoxInterpolator.predict(0.85f, 0.45f, 0.95f, 0.55f,
                1.0f, 0f, 500);
        assertTrue(p[2] <= 1f && p[0] >= 0f);
        assertEquals("width preserved at edge", 0.10f, p[2] - p[0], 1e-4);
    }

    @Test
    public void negativeVelocity_movesLeftAndClampsAtZero() {
        float[] p = BoxInterpolator.predict(0.05f, 0.40f, 0.15f, 0.50f,
                -1.0f, 0f, 500);
        assertEquals(0f, p[0], 0f);
        assertEquals("width preserved", 0.10f, p[2] - p[0], 1e-4);
    }
}
