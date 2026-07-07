package com.dji.sdk.sample.internal.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Covers R9 — adaptive capture interval + static-scene gating. */
public class AdaptiveIntervalTest {

    @Test
    public void interval_followsMeasuredCycleTimeWithHeadroom() {
        // Typical Helio G99 cycle: ~230 ms capture+infer => ~299 ms, floored to 300
        assertEquals(300, AdaptiveInterval.compute(230f, false));
        // Slow cycle 500 ms => 650 ms, inside [300, 800]
        assertEquals(650, AdaptiveInterval.compute(500f, false));
    }

    @Test
    public void interval_neverBelowSafeFloor() {
        // Even an absurdly fast cycle cannot re-introduce dropped frames
        assertEquals(AdaptiveInterval.FLOOR_MS, AdaptiveInterval.compute(10f, false));
    }

    @Test
    public void interval_cappedInActiveMode() {
        assertEquals(AdaptiveInterval.ACTIVE_CEIL_MS,
                AdaptiveInterval.compute(5000f, false));
    }

    @Test
    public void staticScene_stretchesUpToStaticCeiling() {
        long active = AdaptiveInterval.compute(300f, false);
        long idle = AdaptiveInterval.compute(300f, true);
        assertTrue("static interval longer", idle > active);
        assertEquals(2 * active, idle);
        // and is capped
        assertEquals(AdaptiveInterval.STATIC_CEIL_MS,
                AdaptiveInterval.compute(5000f, true));
    }

    @Test
    public void motionResumes_intervalRestoredWithinOneCycle() {
        // The gate recomputes every cycle from (ema, staticScene): as soon as
        // staticScene flips false the interval returns to the active value.
        long idle = AdaptiveInterval.compute(300f, true);
        long restored = AdaptiveInterval.compute(300f, false);
        assertTrue(idle > restored);
        assertEquals(390, restored); // 300 * 1.3
    }

    @Test
    public void ema_smoothsSpikes() {
        float ema = 300f;
        ema = AdaptiveInterval.updateEma(ema, 900); // one-off spike
        assertTrue("spike damped: " + ema, ema < 500f);
        // sustained slowdown converges
        for (int i = 0; i < 20; i++) ema = AdaptiveInterval.updateEma(ema, 600);
        assertEquals(600f, ema, 10f);
    }

    @Test
    public void signature_identicalFramesAreStatic() {
        int[] a = new int[64], b = new int[64];
        for (int i = 0; i < 64; i++) { a[i] = 120 + (i % 5); b[i] = a[i] + 2; }
        assertTrue(AdaptiveInterval.isStatic(a, b));
    }

    @Test
    public void signature_motionIsNotStatic() {
        int[] a = new int[64], b = new int[64];
        for (int i = 0; i < 64; i++) { a[i] = 120; b[i] = 120 + ((i % 4 == 0) ? 40 : 0); }
        assertFalse(AdaptiveInterval.isStatic(a, b));
    }

    @Test
    public void signature_nullOrMismatchedNeverStatic() {
        assertFalse(AdaptiveInterval.isStatic(null, new int[64]));
        assertFalse(AdaptiveInterval.isStatic(new int[64], new int[32]));
        assertFalse(AdaptiveInterval.isStatic(new int[0], new int[0]));
    }
}
