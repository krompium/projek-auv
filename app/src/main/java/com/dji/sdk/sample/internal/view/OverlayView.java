package com.dji.sdk.sample.internal.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class OverlayView extends View {

    private final Paint boxPaint;
    private final Paint textPaint;
    private final Paint textBgPaint;

    private final int[] TRACK_COLORS = {
            Color.parseColor("#FF3B30"),
            Color.parseColor("#4CD964"),
            Color.parseColor("#007AFF"),
            Color.parseColor("#FFCC00"),
            Color.parseColor("#FF9500"),
            Color.parseColor("#5AC8FA"),
            Color.parseColor("#5856D6")
    };

    private volatile List<YoloDetector.Detection> detections = new ArrayList<>();

    // Zero-lag transition state per trackId: decays the snap-back jump over
    // BLEND_DURATION_MS (110 ms) using cubic ease-out so there is 0 steady-state lag.
    private static final float BLEND_DURATION_MS = 110f;
    private static final float SIZE_SMOOTH_ALPHA = 0.40f;

    private static final class TrackVisualState {
        long lastCaptureTs = -1;
        long blendStartMs = 0;
        float resCx = 0f, resCy = 0f;
        float smoothW = -1f, smoothH = -1f;
        float lastDrawnCx = 0f, lastDrawnCy = 0f;
        int seenCycles = 1;
    }

    private final SparseArray<TrackVisualState> trackStates = new SparseArray<>();

    // Compact one-line debug rollup (fed by BaseFpvView, replaces Toast spam)
    private final Paint debugPaint;
    private final Paint debugBgPaint;
    private volatile String debugInfo = null;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);

        boxPaint = new Paint();
        boxPaint.setColor(Color.parseColor("#FF3B30"));
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4f);
        boxPaint.setAntiAlias(true);

        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(36f);
        textPaint.setAntiAlias(true);
        textPaint.setFakeBoldText(true);

        textBgPaint = new Paint();
        textBgPaint.setColor(Color.parseColor("#CC000000"));
        textBgPaint.setStyle(Paint.Style.FILL);

        debugPaint = new Paint();
        debugPaint.setColor(Color.WHITE);
        debugPaint.setTextSize(26f);
        debugPaint.setAntiAlias(true);
        debugPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        debugBgPaint = new Paint();
        debugBgPaint.setColor(Color.parseColor("#99000000"));
        debugBgPaint.setStyle(Paint.Style.FILL);
    }

    public void updateDetections(List<YoloDetector.Detection> newDetections) {
        if (newDetections != null && !newDetections.isEmpty()) {
            // Merge: keep coasting tracks from previous cycle if they only missed 1 frame
            long now = System.currentTimeMillis();
            List<YoloDetector.Detection> merged = new ArrayList<>(newDetections);
            for (YoloDetector.Detection oldDet : this.detections) {
                if (oldDet.trackId <= 0) continue;
                long refTs = (oldDet.deliveryTimestampMs > 0)
                        ? oldDet.deliveryTimestampMs : oldDet.timestampMs;
                if (now - refTs > 450) continue; // coast at most 1 missed cycle (~320 ms)
                boolean stillPresent = false;
                for (YoloDetector.Detection nd : newDetections) {
                    if (nd.trackId == oldDet.trackId) {
                        stillPresent = true;
                        break;
                    }
                }
                if (!stillPresent) merged.add(oldDet);
            }
            this.detections = merged;
        } else {
            // If 0 detections in this frame, keep active tracks coasting for up to 450 ms
            // instead of blinking the screen blank immediately on a 1-frame miss.
            long now = System.currentTimeMillis();
            List<YoloDetector.Detection> surviving = new ArrayList<>();
            for (YoloDetector.Detection oldDet : this.detections) {
                long refTs = (oldDet.deliveryTimestampMs > 0)
                        ? oldDet.deliveryTimestampMs : oldDet.timestampMs;
                if (oldDet.trackId > 0 && (now - refTs) <= 450) {
                    surviving.add(oldDet);
                }
            }
            this.detections = surviving;
        }
        postInvalidateOnAnimation();
    }

    /** One-line live metrics rollup rendered at the top-left of the FPV. */
    public void setDebugInfo(String info) {
        this.debugInfo = info;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        String dbg = debugInfo;
        if (dbg != null && !dbg.isEmpty()) {
            float pad = 8f;
            float w = debugPaint.measureText(dbg);
            float h = debugPaint.getTextSize();
            canvas.drawRect(0, 0, w + pad * 2, h + pad * 2, debugBgPaint);
            canvas.drawText(dbg, pad, h + pad / 2, debugPaint);
        }

        List<YoloDetector.Detection> currentDetections = this.detections;
        if (currentDetections == null || currentDetections.isEmpty()) {
            trackStates.clear();
            return;
        }

        float viewWidth  = getWidth();
        float viewHeight = getHeight();
        long now = System.currentTimeMillis();
        boolean anyAlive = false;

        for (YoloDetector.Detection det : currentDetections) {
            if (det.boundingBox == null) continue;

            // Expiration is checked against delivery time so boxes don't blink
            // out while waiting for the next ~210 ms inference to complete.
            long refExpiryTs = (det.deliveryTimestampMs > 0)
                    ? det.deliveryTimestampMs : det.timestampMs;
            long ageSinceDeliveryMs = (refExpiryTs > 0) ? (now - refExpiryTs) : 0;
            if (BoxInterpolator.isExpired(ageSinceDeliveryMs)) {
                if (det.trackId > 0) trackStates.remove(det.trackId);
                continue;
            }
            anyAlive = true;

            // Position extrapolation compensates from actual frame capture time
            long predictDtMs = (det.timestampMs > 0)
                    ? (now - det.timestampMs) : ageSinceDeliveryMs;
            float[] p = BoxInterpolator.predict(
                    det.boundingBox.left, det.boundingBox.top,
                    det.boundingBox.right, det.boundingBox.bottom,
                    det.velocityX, det.velocityY, predictDtMs);

            float predCx = (p[0] + p[2]) * 0.5f;
            float predCy = (p[1] + p[3]) * 0.5f;
            float rawW   = p[2] - p[0];
            float rawH   = p[3] - p[1];

            float drawLeft, drawTop, drawRight, drawBottom;

            if (det.trackId > 0) {
                TrackVisualState st = trackStates.get(det.trackId);
                if (st == null) {
                    st = new TrackVisualState();
                    st.lastCaptureTs = det.timestampMs;
                    st.smoothW = rawW;
                    st.smoothH = rawH;
                    st.lastDrawnCx = predCx;
                    st.lastDrawnCy = predCy;
                    trackStates.put(det.trackId, st);
                } else if (det.timestampMs != st.lastCaptureTs) {
                    st.seenCycles++;
                    st.resCx = st.lastDrawnCx - predCx;
                    st.resCy = st.lastDrawnCy - predCy;
                    st.blendStartMs = now;
                    st.lastCaptureTs = det.timestampMs;
                    st.smoothW += SIZE_SMOOTH_ALPHA * (rawW - st.smoothW);
                    st.smoothH += SIZE_SMOOTH_ALPHA * (rawH - st.smoothH);
                }

                if (st.seenCycles < 2) continue;

                float u = (now - st.blendStartMs) / BLEND_DURATION_MS;
                float decay = (u >= 1f) ? 0f : (u <= 0f ? 1f : (1f - u) * (1f - u) * (1f - u));

                float cx = predCx + st.resCx * decay;
                float cy = predCy + st.resCy * decay;
                st.lastDrawnCx = cx;
                st.lastDrawnCy = cy;

                float halfW = st.smoothW * 0.5f;
                float halfH = st.smoothH * 0.5f;
                drawLeft   = Math.max(0f, Math.min(1f - st.smoothW, cx - halfW));
                drawTop    = Math.max(0f, Math.min(1f - st.smoothH, cy - halfH));
                drawRight  = drawLeft + st.smoothW;
                drawBottom = drawTop  + st.smoothH;

                boxPaint.setColor(TRACK_COLORS[det.trackId % TRACK_COLORS.length]);
            } else {
                drawLeft   = p[0];
                drawTop    = p[1];
                drawRight  = p[2];
                drawBottom = p[3];
                boxPaint.setColor(Color.parseColor("#FF3B30"));
            }

            float left   = drawLeft   * viewWidth;
            float top    = drawTop    * viewHeight;
            float right  = drawRight  * viewWidth;
            float bottom = drawBottom * viewHeight;

            canvas.drawRect(left, top, right, bottom, boxPaint);

            String labelText;
            int confidencePercent = Math.round(det.confidence * 100);

            if (det.trackId > 0) {
                labelText = String.format(Locale.US, "%s #%d (%d%%)",
                        det.label, det.trackId, confidencePercent);
            } else {
                labelText = String.format(Locale.US, "%s (%d%%)",
                        det.label, confidencePercent);
            }

            float textWidth  = textPaint.measureText(labelText);
            float textHeight = textPaint.getTextSize();
            float padding    = 6f;

            float bgLeft   = left;
            float bgTop    = Math.max(0, top - textHeight - padding * 2);
            float bgRight  = left + textWidth + padding * 2;
            float bgBottom = Math.max(textHeight + padding * 2, top);

            textBgPaint.setColor(boxPaint.getColor());
            textBgPaint.setAlpha(200);

            canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, textBgPaint);
            canvas.drawText(labelText, bgLeft + padding, bgBottom - padding, textPaint);
        }

        // VSync-locked 60 FPS redraw while any track is active
        if (anyAlive) postInvalidateDelayed(40);    }
}