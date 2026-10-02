package com.dji.sdk.sample.internal.view;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java multi-object tracker operating entirely in normalized [0,1]
 * image coordinates. No Android dependencies, so it is unit-testable on the
 * plain JVM.
 *
 * <p>Root cause fixed by this class (tracking coordinate-space bug): the
 * previous {@code assignTrackIds()} compared centers of normalized [0,1]
 * boxes against {@code MAX_DISTANCE = 150.0f} <i>pixels</i>. In normalized
 * space the largest possible distance is ~1.414, so the guard could never
 * reject a match and every detection greedily matched the nearest existing
 * track — corrupting track IDs and the Firebase dedup keyed on them.</p>
 *
 * <p>This tracker keeps a single unit system (normalized) throughout and
 * associates greedily by ascending distance over all (detection, track)
 * pairs, which reduces ID swaps when objects are close together. It also
 * maintains a per-track velocity estimate (normalized units per second) used
 * by the overlay for inter-frame prediction.</p>
 */
public class BoxTracker {

    /**
     * Maximum center distance (in normalized units, i.e. fraction of the
     * frame) for a detection-to-track match. ~0.08 of the unit square
     * corresponds to ~0.06 of the frame diagonal.
     */
    public static final float DEFAULT_MAX_MATCH_DISTANCE = 0.08f;

    /** Cycles a track may go undetected before it is pruned. */
    public static final int MAX_LOST_FRAMES = 5;

    /** Exponential smoothing factor for velocity updates (0..1, higher = snappier). */
    private static final float VELOCITY_ALPHA = 0.5f;

    /** Gate grows with time since the track's last update (normalized units / second). */
    private static final float MAX_APPARENT_SPEED = 0.35f;
    /** Upper bound for the time-scaled gate. */
    private static final float MAX_MATCH_DISTANCE_CAP = 0.25f;

    /** Per-detection association result. */
    public static class Assignment {
        public final int trackId;
        /** Track velocity in normalized units per second. */
        public final float vx, vy;
        /** True when this detection spawned a brand-new track. */
        public final boolean isNew;

        Assignment(int trackId, float vx, float vy, boolean isNew) {
            this.trackId = trackId;
            this.vx = vx;
            this.vy = vy;
            this.isNew = isNew;
        }
    }

    private static class Track {
        final int id;
        float cx, cy;
        float vx, vy;
        long lastUpdateMs;
        int lostFrames;

        Track(int id, float cx, float cy, long nowMs) {
            this.id = id;
            this.cx = cx;
            this.cy = cy;
            this.lastUpdateMs = nowMs;
        }
    }

    private static class Pair implements Comparable<Pair> {
        final int detIdx, trackIdx;
        final float dist;

        Pair(int detIdx, int trackIdx, float dist) {
            this.detIdx = detIdx;
            this.trackIdx = trackIdx;
            this.dist = dist;
        }

        @Override
        public int compareTo(Pair o) {
            return Float.compare(dist, o.dist);
        }
    }

    private final List<Track> tracks = new ArrayList<>();
    private int nextTrackId = 1;
    private final float maxMatchDistance;

    public BoxTracker() {
        this(DEFAULT_MAX_MATCH_DISTANCE);
    }

    public BoxTracker(float maxMatchDistance) {
        this.maxMatchDistance = maxMatchDistance;
    }

    /**
     * Associates detections with existing tracks.
     *
     * @param boxes normalized boxes, each {@code float[4] = {left, top, right, bottom}}
     * @param nowMs current timestamp (ms) used for velocity estimation
     * @return one {@link Assignment} per input box, in input order
     */
    public synchronized List<Assignment> assign(List<float[]> boxes, long nowMs) {
        List<Assignment> result = new ArrayList<>(boxes.size());
        for (int i = 0; i < boxes.size(); i++) result.add(null);

        // Candidate pairs within the gate, sorted by ascending distance.
        List<Pair> pairs = new ArrayList<>();
        for (int d = 0; d < boxes.size(); d++) {
            float cx = (boxes.get(d)[0] + boxes.get(d)[2]) / 2f;
            float cy = (boxes.get(d)[1] + boxes.get(d)[3]) / 2f;
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
        java.util.Collections.sort(pairs);

        boolean[] detUsed = new boolean[boxes.size()];
        boolean[] trackUsed = new boolean[tracks.size()];

        for (Pair p : pairs) {
            if (detUsed[p.detIdx] || trackUsed[p.trackIdx]) continue;
            detUsed[p.detIdx] = true;
            trackUsed[p.trackIdx] = true;

            Track tr = tracks.get(p.trackIdx);
            float cx = (boxes.get(p.detIdx)[0] + boxes.get(p.detIdx)[2]) / 2f;
            float cy = (boxes.get(p.detIdx)[1] + boxes.get(p.detIdx)[3]) / 2f;

            float dtSec = (nowMs - tr.lastUpdateMs) / 1000f;
            if (dtSec > 0.0f) {
                float instVx = (cx - tr.cx) / dtSec;
                float instVy = (cy - tr.cy) / dtSec;
                tr.vx = VELOCITY_ALPHA * instVx + (1f - VELOCITY_ALPHA) * tr.vx;
                tr.vy = VELOCITY_ALPHA * instVy + (1f - VELOCITY_ALPHA) * tr.vy;
            }
            tr.cx = cx;
            tr.cy = cy;
            tr.lastUpdateMs = nowMs;
            tr.lostFrames = 0;

            result.set(p.detIdx, new Assignment(tr.id, tr.vx, tr.vy, false));
        }

        // Unmatched detections spawn new tracks.
        for (int d = 0; d < boxes.size(); d++) {
            if (detUsed[d]) continue;
            float cx = (boxes.get(d)[0] + boxes.get(d)[2]) / 2f;
            float cy = (boxes.get(d)[1] + boxes.get(d)[3]) / 2f;
            Track tr = new Track(nextTrackId++, cx, cy, nowMs);
            tracks.add(tr);
            result.set(d, new Assignment(tr.id, 0f, 0f, true));
        }

        // Unmatched tracks age out. Tracks touched this cycle (matched or
        // freshly created) carry lastUpdateMs == nowMs and are skipped.
        for (int t = tracks.size() - 1; t >= 0; t--) {
            Track tr = tracks.get(t);
            if (tr.lastUpdateMs != nowMs) {
                tr.lostFrames++;
                if (tr.lostFrames > MAX_LOST_FRAMES) {
                    tracks.remove(t);
                }
            }
        }

        return result;
    }

    /** Clears all tracks AND the ID counter — a true session reset. */
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
