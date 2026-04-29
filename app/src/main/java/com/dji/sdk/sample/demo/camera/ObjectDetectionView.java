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
        // Only hide action bar, don't change orientation
        // Orientation change recreates the Activity and breaks navigation
        DJISampleApplication.getEventBus()
                .post(new MainActivity.RequestStartFullScreenEvent());
        if (getContext() instanceof android.app.Activity) {
            android.app.Activity activity = (android.app.Activity) getContext();
            activity.getWindow().setFlags(
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        DJISampleApplication.getEventBus()
                .post(new MainActivity.RequestEndFullScreenEvent());
        if (getContext() instanceof android.app.Activity) {
            android.app.Activity activity = (android.app.Activity) getContext();
            activity.getWindow().clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        super.onDetachedFromWindow();
    }
}