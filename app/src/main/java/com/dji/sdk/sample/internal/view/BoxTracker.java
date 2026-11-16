package com.dji.sdk.sample.internal.view;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure-Java multi-object tracker operating entirely in normalized [0,1]
 * image coordinates. No Android dependencies, so it is unit-testable on the
 * plain JVM.
 *
 * Includes:
 *  - Original single-stage assign(boxes, nowMs) for 100% unit-test compatibility
 *  - ByteTrack two-stage assign(boxes, scores, highThresh, nowMs) with
 *    Median-Residual Camera Motion Compensation (CMC) and Hybrid IoU-Centroid cost.
 */
public class BoxTracker {

    public static final float DEFAULT_MAX_MATCH_DISTANCE = 0.08f;
    public static final int MAX_LOST_FRAMES = 5;
    private static final float VELOCITY_ALPHA = 0.5f;
    private static final float MAX_APPARENT_SPEED = 0.35f;
    private static final float MAX_MATCH_DISTANCE_CAP = 0.25f;

    /** Per-detection association result. */
    public static class Assignment {
        public final int trackId;
        /** Track velocity in normalized units per second. */
        public final float vx, vy;
        /** True when this detection spawned a brand-new track. */
        public final boolean isNew;
        /** Number of times this track has been matched (>= 1). */
        public final int hits;

        Assignment(int trackId, float vx, float vy, boolean isNew) {
            this(trackId, vx, vy, isNew, 1);
        }

        Assignment(int trackId, float vx, float vy, boolean isNew, int hits) {
            this.trackId = trackId;
            this.vx = vx;
            this.vy = vy;
            this.isNew = isNew;
            this.hits = hits;
        }
    }

    private static class Track {
        final int id;
        float cx, cy, w, h;
        float vx, vy;
        long lastUpdateMs;
        int lostFrames;
        int hits;

        Track(int id, float cx, float cy, float w, float h, long nowMs) {
            this.id = id;
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
            this.lastUpdateMs = nowMs;
            this.hits = 1;
        }
    }

    private static class Pair implements Comparable<Pair> {
        final int detIdx, trackIdx;
        final float cost;

        Pair(int detIdx, int trackIdx, float cost) {
            this.detIdx = detIdx;
            this.trackIdx = trackIdx;
            this.cost = cost;
        }

        @Override
        public int compareTo(Pair o) {
            return Float.compare(cost, o.cost);
        }
    }

    private final List<Track> tracks = new ArrayList<>();
    private int nextTrackId = 1;
    private final float maxMatchDistance;
    private final int maxLostFrames;

    public BoxTracker() {
        this(DEFAULT_MAX_MATCH_DISTANCE, MAX_LOST_FRAMES);
    }

    public BoxTracker(float maxMatchDistance) {
        this(maxMatchDistance, MAX_LOST_FRAMES);
    }

    public BoxTracker(float maxMatchDistance, int maxLostFrames) {
        this.maxMatchDistance = maxMatchDistance;
        this.maxLostFrames = maxLostFrames;
    }

    /**
     * Standard single-stage association (preserves exact behavior for 46 JVM unit tests).
     */
    public synchronized List<Assignment> assign(List<float[]> boxes, long nowMs) {
        List<Assignment> result = new ArrayList<>(boxes.size());
        for (int i = 0; i < boxes.size(); i++) result.add(null);

        List<Pair> pairs = new ArrayList<>();
        for (int d = 0; d < boxes.size(); d++) {
            float[] b = boxes.get(d);
            float cx = (b[0] + b[2]) / 2f;
            float cy = (b[1] + b[3]) / 2f;
            for (int t = 0; t < tracks.size(); t++) {
                Track tr = tracks.get(t);
                float dtSec = Math.max(0f, (nowMs - tr.lastUpdateMs) / 1000f);
                float gate = Math.min(MAX_MATCH_DISTANCE_CAP,
                        Math.max(maxMatchDistance, MAX_APPARENT_SPEED * dtSec));
                float distLast = (float) Math.hypot(cx - tr.cx, cy - tr.cy);
                float distPred = (float) Math.hypot(
                        cx - (tr.cx + tr.vx * dtSec), cy - (tr.cy + tr.vy * dtSec));
                float dist = Math.min(distLast, distPred);
                if (dist <= gate) {
                    pairs.add(new Pair(d, t, dist));
                }
            }
        }
        Collections.sort(pairs);

        boolean[] detUsed = new boolean[boxes.size()];
        boolean[] trackUsed = new boolean[tracks.size()];

        float sumVx = 0f, sumVy = 0f;
        int matchedCount = 0;

        for (Pair p : pairs) {
            if (detUsed[p.detIdx] || trackUsed[p.trackIdx]) continue;
            detUsed[p.detIdx] = true;
            trackUsed[p.trackIdx] = true;

            Track tr = tracks.get(p.trackIdx);
            float[] b = boxes.get(p.detIdx);
            float cx = (b[0] + b[2]) / 2f;
            float cy = (b[1] + b[3]) / 2f;

            float dtSec = (nowMs - tr.lastUpdateMs) / 1000f;
            if (dtSec > 0.0f) {
                float instVx = (cx - tr.cx) / dtSec;
                float instVy = (cy - tr.cy) / dtSec;
                tr.vx = VELOCITY_ALPHA * instVx + (1f - VELOCITY_ALPHA) * tr.vx;
                tr.vy = VELOCITY_ALPHA * instVy + (1f - VELOCITY_ALPHA) * tr.vy;
            }
            tr.cx = cx;
            tr.cy = cy;
            tr.w = Math.max(0.01f, b[2] - b[0]);
            tr.h = Math.max(0.01f, b[3] - b[1]);
            tr.lastUpdateMs = nowMs;
            tr.lostFrames = 0;
            tr.hits++;

            sumVx += tr.vx;
            sumVy += tr.vy;
            matchedCount++;

            result.set(p.detIdx, new Assignment(tr.id, tr.vx, tr.vy, false, tr.hits));
        }

        float meanVx = (matchedCount > 0) ? (sumVx / matchedCount) : 0f;
        float meanVy = (matchedCount > 0) ? (sumVy / matchedCount) : 0f;

        for (int d = 0; d < boxes.size(); d++) {
            if (detUsed[d]) continue;
            float[] b = boxes.get(d);
            float cx = (b[0] + b[2]) / 2f;
            float cy = (b[1] + b[3]) / 2f;
            float w = Math.max(0.01f, b[2] - b[0]);
            float h = Math.max(0.01f, b[3] - b[1]);
            Track tr = new Track(nextTrackId++, cx, cy, w, h, nowMs);
            tr.vx = meanVx;
            tr.vy = meanVy;
            tracks.add(tr);
            result.set(d, new Assignment(tr.id, tr.vx, tr.vy, true, tr.hits));
        }

        for (int t = tracks.size() - 1; t >= 0; t--) {
            Track tr = tracks.get(t);
            if (tr.lastUpdateMs != nowMs) {
                tr.lostFrames++;
                if (tr.lostFrames > maxLostFrames) {
                    tracks.remove(t);
                }
            }
        }

        return result;
    }

    /**
     * ByteTrack Two-Stage Association + Median-Residual Camera Motion Compensation (CMC).
     */
    public synchronized List<Assignment> assign(List<float[]> boxes, float[] scores,
                                                float highThresh, long nowMs) {
        if (scores == null || scores.length != boxes.size()) {
            return assign(boxes, nowMs);
        }

        int nDet = boxes.size();
        int nTrk = tracks.size();
        List<Assignment> result = new ArrayList<>(nDet);
        for (int i = 0; i < nDet; i++) result.add(null);

        boolean[] detUsed = new boolean[nDet];
        boolean[] trackUsed = new boolean[nTrk];

        // --- STAGE 1: Match high-confidence detections (score >= highThresh) to all tracks ---
        List<Pair> stage1Pairs = new ArrayList<>();
        for (int d = 0; d < nDet; d++) {
            if (scores[d] < highThresh) continue;
            float[] b = boxes.get(d);
            float cx = (b[0] + b[2]) * 0.5f;
            float cy = (b[1] + b[3]) * 0.5f;

            for (int t = 0; t < nTrk; t++) {
                Track tr = tracks.get(t);
                float dtSec = Math.max(0f, (nowMs - tr.lastUpdateMs) / 1000f);
                float gate = Math.min(MAX_MATCH_DISTANCE_CAP,
                        Math.max(maxMatchDistance, MAX_APPARENT_SPEED * dtSec));
                float predCx = tr.cx + tr.vx * dtSec;
                float predCy = tr.cy + tr.vy * dtSec;

                float distLast = (float) Math.hypot(cx - tr.cx, cy - tr.cy);
                float distPred = (float) Math.hypot(cx - predCx, cy - predCy);
                float dist = Math.min(distLast, distPred);

                float iou = computeBoxIoU(b, predCx, predCy, tr.w, tr.h);
                if (dist <= gate || iou >= 0.20f) {
                    float cost = dist * (1.0f - 0.35f * iou);
                    stage1Pairs.add(new Pair(d, t, cost));
                }
            }
        }
        Collections.sort(stage1Pairs);

        float[] dxSamples = new float[Math.min(nDet, nTrk)];
        float[] dySamples = new float[Math.min(nDet, nTrk)];
        float[] vxSamples = new float[Math.min(nDet, nTrk)];
        float[] vySamples = new float[Math.min(nDet, nTrk)];
        int matchedStage1 = 0;

        for (Pair p : stage1Pairs) {
            if (detUsed[p.detIdx] || trackUsed[p.trackIdx]) continue;
            detUsed[p.detIdx] = true;
            trackUsed[p.trackIdx] = true;

            Track tr = tracks.get(p.trackIdx);
            float[] b = boxes.get(p.detIdx);
            float cx = (b[0] + b[2]) * 0.5f;
            float cy = (b[1] + b[3]) * 0.5f;

            dxSamples[matchedStage1] = (cx - tr.cx);
            dySamples[matchedStage1] = (cy - tr.cy);

            updateTrackWithBox(tr, cx, cy, b[2] - b[0], b[3] - b[1], nowMs);
            vxSamples[matchedStage1] = tr.vx;
            vySamples[matchedStage1] = tr.vy;
            matchedStage1++;

            result.set(p.detIdx, new Assignment(tr.id, tr.vx, tr.vy, false, tr.hits));
        }

        // --- MEDIAN-RESIDUAL CAMERA MOTION COMPENSATION (CMC) ---
        // Median is outlier-resistant when one item moves due to rotor downwash.
        float camDx = medianOfPrefix(dxSamples, matchedStage1);
        float camDy = medianOfPrefix(dySamples, matchedStage1);
        float medVx = medianOfPrefix(vxSamples, matchedStage1);
        float medVy = medianOfPrefix(vySamples, matchedStage1);

        // --- STAGE 2 (ByteTrack): Match low-confidence detections ONLY to confirmed tracks ---
        List<Pair> stage2Pairs = new ArrayList<>();
        for (int d = 0; d < nDet; d++) {
            if (detUsed[d] || scores[d] >= highThresh) continue;
            float[] b = boxes.get(d);
            float cx = (b[0] + b[2]) * 0.5f;
            float cy = (b[1] + b[3]) * 0.5f;

            for (int t = 0; t < nTrk; t++) {
                if (trackUsed[t]) continue;
                Track tr = tracks.get(t);
                // Only rescue tracks that were already confirmed (hits >= 2)
                if (tr.hits < 2) continue;

                float dtSec = Math.max(0f, (nowMs - tr.lastUpdateMs) / 1000f);
                float predCx = (matchedStage1 > 0) ? (tr.cx + camDx) : (tr.cx + tr.vx * dtSec);
                float predCy = (matchedStage1 > 0) ? (tr.cy + camDy) : (tr.cy + tr.vy * dtSec);

                float dist = (float) Math.hypot(cx - predCx, cy - predCy);
                float iou = computeBoxIoU(b, predCx, predCy, tr.w, tr.h);
                if (dist <= maxMatchDistance || iou >= 0.25f) {
                    float cost = dist * (1.0f - 0.40f * iou);
                    stage2Pairs.add(new Pair(d, t, cost));
                }
            }
        }
        Collections.sort(stage2Pairs);

        for (Pair p : stage2Pairs) {
            if (detUsed[p.detIdx] || trackUsed[p.trackIdx]) continue;
            detUsed[p.detIdx] = true;
            trackUsed[p.trackIdx] = true;

            Track tr = tracks.get(p.trackIdx);
            float[] b = boxes.get(p.detIdx);
            float cx = (b[0] + b[2]) * 0.5f;
            float cy = (b[1] + b[3]) * 0.5f;

            updateTrackWithBox(tr, cx, cy, b[2] - b[0], b[3] - b[1], nowMs);
            result.set(p.detIdx, new Assignment(tr.id, tr.vx, tr.vy, false, tr.hits));
        }

        // --- SPAWN NEW TRACKS: Only unmatched HIGH-confidence detections spawn tracks ---
        for (int d = 0; d < nDet; d++) {
            if (detUsed[d]) continue;
            if (scores[d] < highThresh) {
                result.set(d, new Assignment(0, 0f, 0f, false, 0));
                continue;
            }
            float[] b = boxes.get(d);
            float cx = (b[0] + b[2]) * 0.5f;
            float cy = (b[1] + b[3]) * 0.5f;
            float w = Math.max(0.01f, b[2] - b[0]);
            float h = Math.max(0.01f, b[3] - b[1]);
            Track tr = new Track(nextTrackId++, cx, cy, w, h, nowMs);
            tr.vx = medVx;
            tr.vy = medVy;
            tracks.add(tr);
            result.set(d, new Assignment(tr.id, tr.vx, tr.vy, true, tr.hits));
        }

        // --- AGE OUT UNMATCHED TRACKS ---
        for (int t = tracks.size() - 1; t >= 0; t--) {
            Track tr = tracks.get(t);
            if (tr.lastUpdateMs != nowMs) {
                tr.lostFrames++;
                int limit = (tr.hits >= 2) ? maxLostFrames : Math.min(2, maxLostFrames);
                if (tr.lostFrames > limit) {
                    tracks.remove(t);
                }
            }
        }

        return result;
    }

    private static float medianOfPrefix(float[] arr, int len) {
        if (len <= 0) return 0f;
        if (len == 1) return arr[0];
        if (len == 2) return (arr[0] + arr[1]) * 0.5f;
        Arrays.sort(arr, 0, len);
        int mid = len >> 1;
        return ((len & 1) == 1) ? arr[mid] : (arr[mid - 1] + arr[mid]) * 0.5f;
    }

    private void updateTrackWithBox(Track tr, float cx, float cy, float w, float h, long nowMs) {
        float dtSec = (nowMs - tr.lastUpdateMs) / 1000f;
        if (dtSec > 0.0f) {
            float instVx = (cx - tr.cx) / dtSec;
            float instVy = (cy - tr.cy) / dtSec;
            // Clamp instantaneous velocity to prevent sudden jumps on 400ms gate
            instVx = Math.max(-MAX_APPARENT_SPEED * 1.5f, Math.min(MAX_APPARENT_SPEED * 1.5f, instVx));
            instVy = Math.max(-MAX_APPARENT_SPEED * 1.5f, Math.min(MAX_APPARENT_SPEED * 1.5f, instVy));
            tr.vx = VELOCITY_ALPHA * instVx + (1f - VELOCITY_ALPHA) * tr.vx;
            tr.vy = VELOCITY_ALPHA * instVy + (1f - VELOCITY_ALPHA) * tr.vy;
        }
        tr.cx = cx;
        tr.cy = cy;
        tr.w = 0.5f * Math.max(0.01f, w) + 0.5f * tr.w;
        tr.h = 0.5f * Math.max(0.01f, h) + 0.5f * tr.h;
        tr.lastUpdateMs = nowMs;
        tr.lostFrames = 0;
        tr.hits++;
    }

    private static float computeBoxIoU(float[] b, float trCx, float trCy, float trW, float trH) {
        float trL = trCx - trW * 0.5f;
        float trT = trCy - trH * 0.5f;
        float trR = trCx + trW * 0.5f;
        float trB = trCy + trH * 0.5f;

        float interL = Math.max(b[0], trL);
        float interT = Math.max(b[1], trT);
        float interR = Math.min(b[2], trR);
        float interB = Math.min(b[3], trB);
        if (interR <= interL || interB <= interT) return 0f;

        float interArea = (interR - interL) * (interB - interT);
        float bArea = Math.max(1e-6f, (b[2] - b[0]) * (b[3] - b[1]));
        float tArea = Math.max(1e-6f, trW * trH);
        return interArea / (bArea + tArea - interArea);
    }

    public synchronized void reset() {
        tracks.clear();
        nextTrackId = 1;
    }

    public synchronized int getActiveCount() {
        return tracks.size();
    }

    public synchronized int getTotalCreated() {
        return nextTrackId - 1;
    }
}