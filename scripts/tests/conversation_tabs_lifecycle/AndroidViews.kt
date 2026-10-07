package android.view

import android.content.Context

open class View(val context: Context = Context()) {
    var parent: ViewGroup? = null
    var layoutParams: ViewGroup.LayoutParams = ViewGroup.LayoutParams(0, 0)
    var visibility = VISIBLE
    var tag: Any? = null
    var id = 0
    var overScrollMode = 0
    fun bringToFront() = Unit
    companion object {
        const val VISIBLE = 0
        const val GONE = 8
        const val OVER_SCROLL_NEVER = 2
        private var nextId = 100
        fun generateViewId() = nextId++
    }
}
open class ViewGroup(context: Context = Context()) : View(context) {
    private val children = arrayListOf<View>()
    var additions = 0
        private set
    val childCount get() = children.size
    fun getChildAt(index: Int) = children[index]
    fun addView(view: View, params: LayoutParams) = addView(view, children.size, params)
    fun addView(view: View, index: Int, params: LayoutParams) {
        check(view.parent == null) { "View already has a parent" }
        children.add(index, view)
        view.parent = this
        view.layoutParams = params
        additions++
    }
    fun removeView(view: View) { if (children.remove(view)) view.parent = null }
    fun removeAllViews() { children.toList().forEach(::removeView) }
    @Suppress("UNCHECKED_CAST")
    fun <T : View> findViewWithTag(value: Any): T? =
        if (tag == value) this as T else children.firstNotNullOfOrNull {
            if (it.tag == value) it as T else (it as? ViewGroup)?.findViewWithTag<T>(value)
        }
    open class LayoutParams(val width: Int, val height: Int) {
        companion object { const val MATCH_PARENT = -1; const val WRAP_CONTENT = -2 }
    }
}
object Gravity { const val CENTER_VERTICAL = 16 }
