package com.dji.sdk.sample.demo.lidar

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.annotation.NonNull
import com.dji.sdk.sample.R
import com.dji.sdk.sample.internal.view.PresentableView

class LidarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), PresentableView {

    override fun getDescription(): Int {
        return R.string.component_listview_lidar_view
    }

    @NonNull
    override fun getHint(): String {
        return this.javaClass.simpleName + ".java"
    }
}