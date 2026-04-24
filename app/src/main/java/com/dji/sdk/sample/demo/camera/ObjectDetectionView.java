package com.dji.sdk.sample.demo.camera;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.dji.sdk.sample.R;
import com.dji.sdk.sample.internal.controller.DJISampleApplication;
import com.dji.sdk.sample.internal.controller.MainActivity;
import com.dji.sdk.sample.internal.view.PresentableView;

public class ObjectDetectionView extends FrameLayout implements PresentableView {

    public ObjectDetectionView(Context context) {
        super(context);
        init(context);
    }

    public ObjectDetectionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_object_detection, this, true);
    }

    @Override
    public int getDescription() {
        return R.string.object_detection_title;
    }

    @NonNull
    @Override
    public String getHint() {
        return this.getClass().getSimpleName() + ".java";
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        DJISampleApplication.getEventBus()
                .post(new MainActivity.RequestStartFullScreenEvent());
    }

    @Override
    protected void onDetachedFromWindow() {
        DJISampleApplication.getEventBus()
                .post(new MainActivity.RequestEndFullScreenEvent());
        super.onDetachedFromWindow();
    }
}