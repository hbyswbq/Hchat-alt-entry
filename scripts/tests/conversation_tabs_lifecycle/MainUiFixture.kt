package com.tencent.mm.ui.conversation

import android.view.View
import android.widget.RelativeLayout

open class MainUiBase {
    fun onResume() = Unit
    fun onDestroy() = Unit
}
class OtherUi : MainUiBase()
class MainUI : MainUiBase() {
    var nativeRoot: MainUIView? = null
    var inflationCount = 0
    fun getLayoutView(): View {
        inflationCount++
        return MainUIView().also { nativeRoot = it }
    }
}
class MainUIView : RelativeLayout() {
    val list = View()
    init {
        addView(list, LayoutParams(200, 400).apply { addRule(ALIGN_PARENT_TOP) })
    }
}
