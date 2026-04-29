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

public class OverlayView extends View {

    private final Paint boxPaint;
    private final Paint textPaint;
    private final Paint textBgPaint;

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
        this.detections = new ArrayList<>(newDetections);

//        if (!newDetections.isEmpty()) {
//            post(new Runnable() {
//                @Override
//                public void run() {
//                    android.widget.Toast.makeText(getContext(),
//                            "Overlay received: " + detections.size() + " boxes",
//                            android.widget.Toast.LENGTH_SHORT).show();
//                }
//            });
//        }

        postInvalidate(); // triggers redraw from any thread
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        android.util.Log.d("OVERLAY", "onDraw called, detections="
                + detections.size() + " w=" + getWidth() + " h=" + getHeight());

        float viewWidth  = getWidth();
        float viewHeight = getHeight();

        for (YoloDetector.Detection det : detections) {
            android.util.Log.d("OVERLAY_COORDS",
                    "box: " + det.boundingBox.toString() +
                            " viewW=" + viewWidth + " viewH=" + viewHeight);
        }

        for (YoloDetector.Detection det : detections) {
            RectF box = det.boundingBox;

            if (det == detections.get(0)) {
                android.widget.Toast.makeText(getContext(),
                        det.label + " box:" + String.format("%.2f,%.2f,%.2f,%.2f",
                                box.left, box.top, box.right, box.bottom)
                                + " view:" + getWidth() + "x" + getHeight(),
                        android.widget.Toast.LENGTH_SHORT).show();
            }

            // Scale normalized [0,1] coords to view pixel coords
            float left   = box.left   * viewWidth;
            float top    = box.top    * viewHeight;
            float right  = box.right  * viewWidth;
            float bottom = box.bottom * viewHeight;

            // Draw bounding box
            canvas.drawRect(left, top, right, bottom, boxPaint);

            // Build label string
            String labelText = det.label + " "
                    + Math.round(det.confidence * 100) + "%";

            // Measure text for background rectangle
            float textWidth  = textPaint.measureText(labelText);
            float textHeight = textPaint.getTextSize();
            float padding    = 6f;

            float bgLeft   = left;
            float bgTop    = Math.max(0, top - textHeight - padding * 2);
            float bgRight  = left + textWidth + padding * 2;
            float bgBottom = Math.max(textHeight + padding * 2, top);

            // Draw label background + text
            canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, textBgPaint);
            canvas.drawText(labelText,
                    bgLeft + padding,
                    bgBottom - padding,
                    textPaint);
        }
    }
}