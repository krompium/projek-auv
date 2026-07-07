package com.dji.sdk.sample.internal.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Covers R1 — stable track IDs after the coordinate-space fix.
 * All coordinates are normalized [0,1]; cycle period mirrors the real
 * pipeline (~400 ms).
 */
public class BoxTrackerTest {

    private static List<float[]> boxes(float[]... b) {
        List<float[]> list = new ArrayList<>();
        Collections.addAll(list, b);
        return list;
    }

    private static float[] boxAt(float cx, float cy) {
        float half = 0.05f;
        return new float[]{cx - half, cy - half, cx + half, cy + half};
    }

    @Test
    public void happyPath_nearbyDetectionKeepsSameId() {
        BoxTracker tracker = new BoxTracker();
        long t = 0;

        List<BoxTracker.Assignment> a1 = tracker.assign(boxes(boxAt(0.5f, 0.5f)), t);
        assertEquals(1, a1.get(0).trackId);

        // Slight movement (0.02, 0.01) — must keep ID 1
        List<BoxTracker.Assignment> a2 =
                tracker.assign(boxes(boxAt(0.52f, 0.51f)), t + 400);
        assertEquals(1, a2.get(0).trackId);
        assertEquals(1, tracker.getActiveCount());
        assertEquals(1, tracker.getTotalCreated());
    }

    @Test
    public void stationaryObject_keepsOneIdAcrossManyCycles() {
        BoxTracker tracker = new BoxTracker();
        for (int i = 0; i < 25; i++) {
            // tiny jitter around (0.5, 0.5), as a real stationary pile produces
            float jitter = (i % 2 == 0) ? 0.004f : -0.004f;
            List<BoxTracker.Assignment> a =
                    tracker.assign(boxes(boxAt(0.5f + jitter, 0.5f)), i * 400L);
            assertEquals("cycle " + i, 1, a.get(0).trackId);
        }
        assertEquals(1, tracker.getActiveCount());
        assertEquals("no ID churn over 25 cycles", 1, tracker.getTotalCreated());
    }

    @Test
    public void farDetection_spawnsNewIdInsteadOfStealingTrack() {
        BoxTracker tracker = new BoxTracker();
        tracker.assign(boxes(boxAt(0.2f, 0.2f)), 0);

        // 0.9 away — with the old pixel threshold this would have matched
        List<BoxTracker.Assignment> a =
                tracker.assign(boxes(boxAt(0.9f, 0.9f)), 400);
        assertEquals(2, a.get(0).trackId);
        assertTrue(a.get(0).isNew);
    }

    @Test
    public void twoSeparateObjects_doNotCollapseIntoOneTrack() {
        BoxTracker tracker = new BoxTracker();
        List<BoxTracker.Assignment> a1 =
                tracker.assign(boxes(boxAt(0.2f, 0.5f), boxAt(0.7f, 0.5f)), 0);
        assertNotEquals(a1.get(0).trackId, a1.get(1).trackId);

        List<BoxTracker.Assignment> a2 =
                tracker.assign(boxes(boxAt(0.21f, 0.5f), boxAt(0.71f, 0.5f)), 400);
        assertEquals(a1.get(0).trackId, a2.get(0).trackId);
        assertEquals(a1.get(1).trackId, a2.get(1).trackId);
        assertEquals(2, tracker.getActiveCount());
    }

    @Test
    public void closeObjects_greedyByDistanceAvoidsSwaps() {
        BoxTracker tracker = new BoxTracker();
        tracker.assign(boxes(boxAt(0.40f, 0.5f), boxAt(0.50f, 0.5f)), 0);

        // Both move slightly right; nearest-pair-first must map 1->1, 2->2
        List<BoxTracker.Assignment> a =
                tracker.assign(boxes(boxAt(0.42f, 0.5f), boxAt(0.52f, 0.5f)), 400);
        assertEquals(1, a.get(0).trackId);
        assertEquals(2, a.get(1).trackId);
    }

    @Test
    public void lostTrack_prunedAfterMaxLostFrames_reappearanceGetsFreshId() {
        BoxTracker tracker = new BoxTracker();
        tracker.assign(boxes(boxAt(0.5f, 0.5f)), 0);

        // Object disappears for MAX_LOST_FRAMES + 1 cycles
        long t = 400;
        for (int i = 0; i <= BoxTracker.MAX_LOST_FRAMES; i++) {
            tracker.assign(boxes(), t);
            t += 400;
        }
        assertEquals(0, tracker.getActiveCount());

        List<BoxTracker.Assignment> a = tracker.assign(boxes(boxAt(0.5f, 0.5f)), t);
        assertEquals("fresh ID after pruning", 2, a.get(0).trackId);
    }

    @Test
    public void emptyDetectionList_stateStaysConsistent() {
        BoxTracker tracker = new BoxTracker();
        tracker.assign(boxes(boxAt(0.5f, 0.5f)), 0);
        tracker.assign(boxes(), 400); // lostFrames=1, still active
        assertEquals(1, tracker.getActiveCount());

        List<BoxTracker.Assignment> a = tracker.assign(boxes(boxAt(0.5f, 0.5f)), 800);
        assertEquals("recovers same track within MAX_LOST_FRAMES", 1, a.get(0).trackId);
    }

    @Test
    public void velocity_estimatedTowardMotionDirection() {
        BoxTracker tracker = new BoxTracker();
        tracker.assign(boxes(boxAt(0.30f, 0.5f)), 0);
        // moves +0.04 in x over 400 ms => raw 0.1 units/s; EMA(0.5) => 0.05
        List<BoxTracker.Assignment> a =
                tracker.assign(boxes(boxAt(0.34f, 0.5f)), 400);
        assertTrue("vx positive", a.get(0).vx > 0.0f);
        assertEquals(0.05f, a.get(0).vx, 0.01f);
        assertEquals(0.0f, a.get(0).vy, 0.005f);
    }

    @Test
    public void reset_clearsTracksAndIdCounter() {
        BoxTracker tracker = new BoxTracker();
        tracker.assign(boxes(boxAt(0.2f, 0.2f), boxAt(0.8f, 0.8f)), 0);
        assertEquals(2, tracker.getTotalCreated());

        tracker.reset();
        assertEquals(0, tracker.getActiveCount());
        assertEquals(0, tracker.getTotalCreated());

        List<BoxTracker.Assignment> a = tracker.assign(boxes(boxAt(0.5f, 0.5f)), 400);
        assertEquals("IDs restart at 1 after reset", 1, a.get(0).trackId);
    }
}
