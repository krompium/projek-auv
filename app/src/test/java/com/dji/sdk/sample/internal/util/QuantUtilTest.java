package com.dji.sdk.sample.internal.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Covers R4 — int8 quantization correctness. */
public class QuantUtilTest {

    // Typical YOLOv8 int8 export params: scale ~1/255, zeroPoint -128
    private static final float SCALE = 0.003921569f; // 1/255
    private static final int ZP = -128;

    @Test
    public void roundTrip_preservesNormalizedValueWithinOneStep() {
        for (int c = 0; c <= 255; c += 5) {
            byte q = QuantUtil.quantizeChannel(c, SCALE, ZP);
            float back = QuantUtil.dequantize(q, ZP, SCALE);
            assertEquals("channel " + c, c / 255.0f, back, SCALE * 1.01f);
        }
    }

    @Test
    public void extremes_mapToInt8Range() {
        assertEquals(-128, QuantUtil.quantizeChannel(0, SCALE, ZP));
        assertEquals(127, QuantUtil.quantizeChannel(255, SCALE, ZP));
    }

    @Test
    public void midGray_mapsNearZero() {
        byte q = QuantUtil.quantizeChannel(128, SCALE, ZP);
        assertTrue("mid gray near 0, got " + q, Math.abs(q) <= 1);
    }

    @Test
    public void clamping_neverOverflows() {
        // Pathological scale that would overflow without the clamp
        byte q = QuantUtil.quantizeChannel(255, 0.001f, 0);
        assertEquals(127, q);
        byte q2 = QuantUtil.quantizeChannel(0, 0.001f, -500);
        assertEquals(-128, q2);
    }

    /**
     * Demonstrates the OLD bug: (byte)(c + zeroPoint) is NOT the affine
     * quantization — for c=255 (white) it produced 127 only by accident of
     * zeroPoint=-128, but for zeroPoint=0 exports it wraps to -1.
     */
    @Test
    public void oldFormula_wasWrongForZeroPointZeroExports() {
        int c = 255;
        byte oldResult = (byte) (c + 0);          // old code, zeroPoint = 0
        assertEquals(-1, oldResult);              // wrapped — corrupt input
        byte fixed = QuantUtil.quantizeChannel(c, SCALE, 0);
        assertEquals(127, fixed);                 // clamped — correct
    }
}
