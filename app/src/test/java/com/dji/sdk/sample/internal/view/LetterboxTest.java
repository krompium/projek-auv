package com.dji.sdk.sample.internal.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Covers R2 — geometric correctness of the letterbox transform. */
public class LetterboxTest {

    /** IoU of two boxes given as {l,t,r,b}. */
    private static float iou(float[] a, float[] b) {
        float il = Math.max(a[0], b[0]), it = Math.max(a[1], b[1]);
        float ir = Math.min(a[2], b[2]), ib = Math.min(a[3], b[3]);
        if (ir <= il || ib <= it) return 0f;
        float inter = (ir - il) * (ib - it);
        float aa = (a[2] - a[0]) * (a[3] - a[1]);
        float ba = (b[2] - b[0]) * (b[3] - b[1]);
        return inter / (aa + ba - inter);
    }

    @Test
    public void geometry_16by9_into640() {
        Letterbox lb = Letterbox.compute(640, 360, 640);
        assertEquals(1.0f, lb.scale, 1e-6);
        assertEquals(640, lb.contentWidth);
        assertEquals(360, lb.contentHeight);
        assertEquals(0, lb.padX);
        assertEquals(140, lb.padY); // (640-360)/2
        assertFalse(lb.isIdentity());
    }

    @Test
    public void centeredObject_mapsBackToCenter_iouAtLeast95() {
        // A box occupying the center 20% of a 16:9 frame.
        Letterbox lb = Letterbox.compute(1920, 1080, 640);
        float[] expected = {0.4f, 0.4f, 0.6f, 0.6f}; // in source-normalized coords

        // Forward-map expected box into model-input normalized coords
        // (what the model would output for a perfect detection).
        float sw = 1920 * lb.scale, sh = 1080 * lb.scale;
        float[] modelBox = {
                (expected[0] * sw + lb.padX) / lb.dstSize,
                (expected[1] * sh + lb.padY) / lb.dstSize,
                (expected[2] * sw + lb.padX) / lb.dstSize,
                (expected[3] * sh + lb.padY) / lb.dstSize};

        float[] mapped = lb.inverseMapNormalized(modelBox.clone());
        assertTrue("IoU >= 0.95, got " + iou(mapped, expected),
                iou(mapped, expected) >= 0.95f);
        assertEquals(0.5f, (mapped[0] + mapped[2]) / 2f, 1e-3);
        assertEquals(0.5f, (mapped[1] + mapped[3]) / 2f, 1e-3);
    }

    @Test
    public void edgeObject_staysInsideUnitRange_notClippedByPadding() {
        Letterbox lb = Letterbox.compute(640, 360, 640);
        // Detection hugging the right edge of the *content* area:
        // model-input x in [ (640-64)/640, 1.0 ], y spanning content rows.
        float top = (lb.padY + 10) / 640f;
        float bottom = (lb.padY + 100) / 640f;
        float[] modelBox = {0.9f, top, 1.0f, bottom};
        float[] mapped = lb.inverseMapNormalized(modelBox.clone());

        for (float v : mapped) {
            assertTrue("in [0,1]: " + v, v >= 0f && v <= 1f);
        }
        assertEquals("right edge maps to source right edge", 1.0f, mapped[2], 1e-3);
        assertTrue("box retains area", mapped[2] > mapped[0] && mapped[3] > mapped[1]);
    }

    @Test
    public void boxFullyInPadding_collapsesToZeroArea() {
        Letterbox lb = Letterbox.compute(640, 360, 640);
        // Entirely inside the top padding band (y < padY=140).
        float[] modelBox = {0.4f, 0.02f, 0.6f, 0.15f};
        float[] mapped = lb.inverseMapNormalized(modelBox.clone());
        assertTrue("degenerate after clamp (top band)", mapped[3] <= mapped[1] + 1e-6);
    }

    @Test
    public void squareInput_reducesToIdentity() {
        Letterbox lb = Letterbox.compute(640, 640, 640);
        assertTrue(lb.isIdentity());
        float[] box = {0.25f, 0.25f, 0.75f, 0.75f};
        float[] mapped = lb.inverseMapNormalized(box.clone());
        assertEquals(box[0], mapped[0], 1e-6);
        assertEquals(box[1], mapped[1], 1e-6);
        assertEquals(box[2], mapped[2], 1e-6);
        assertEquals(box[3], mapped[3], 1e-6);
    }

    @Test
    public void portraitSource_padsHorizontally() {
        Letterbox lb = Letterbox.compute(360, 640, 640);
        assertEquals(0, lb.padY);
        assertEquals(140, lb.padX);
        float[] center = {0.5f, 0.4f, 0.5f, 0.6f};
        // content center column maps to source center column
        float[] mapped = lb.inverseMapNormalized(center.clone());
        assertEquals(0.5f, mapped[0], 1e-3);
    }
}
