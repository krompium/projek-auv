package com.dji.sdk.sample.internal.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
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

    private List<YoloDetector.Detection> detections = new ArrayList<>();

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
    }

    public void updateDetections(List<YoloDetector.Detection> newDetections) {
        this.detections = newDetections;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (detections == null || detections.isEmpty()) return;

        float viewWidth  = getWidth();
        float viewHeight = getHeight();

        for (YoloDetector.Detection det : detections) {
            if (det.boundingBox == null) continue;

            RectF box = det.boundingBox;

            if (det.trackId > 0) {
                boxPaint.setColor(TRACK_COLORS[det.trackId % TRACK_COLORS.length]);
            } else {
                boxPaint.setColor(Color.parseColor("#FF3B30"));
            }

            float left   = box.left   * viewWidth;
            float top    = box.top    * viewHeight;
            float right  = box.right  * viewWidth;
            float bottom = box.bottom * viewHeight;

            canvas.drawRect(left, top, right, bottom, boxPaint);

            String labelText;
            int confidencePercent = Math.round(det.confidence * 100);

            if (det.trackId > 0) {
                labelText = String.format(Locale.US, "%s #%d (%d%%)", det.label, det.trackId, confidencePercent);
            } else {
                labelText = String.format(Locale.US, "%s (%d%%)", det.label, confidencePercent);
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
    }
}