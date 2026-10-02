package h.Hchat.hooks.items.conversationtabs

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.RelativeLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.conversationgroup.ConversationGroupHomeProjection
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** A measured child of MainUI's native root; no row hooks or per-frame listeners. */
internal object ConversationTabsRuntime {
    private const val TAG = "[Hchat:ConversationTabs]"
    private const val ROOT_TAG = "hchat:conversation-tabs-root"
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Hchat-ConversationTabs").apply { isDaemon = true }
    }
    private val revision = AtomicLong()
    private val hosts = WeakHashMap<Any, Host>() // Main-thread only; values never capture keys.
    private val bitmaps = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var context: FeatureContext? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var account = ""
    private var selectedId = "all"
    private var config = ConversationTabsConfig()
    private var loadedIcons: Map<String, Bitmap> = emptyMap()
    private var compiledFilters: Map<String, ConversationTabFilter> = emptyMap()
    @Volatile private var filter: ConversationTabFilter? = null
    @Volatile private var ready = false
    private val refresh = Runnable { ConversationGroupHomeProjection.refresh() }

    private data class Host(
        val root: ViewGroup,
        val strip: HorizontalScrollView,
        val content: LinearLayout,
        val shifted: List<Pair<View, RelativeLayout.LayoutParams>>,
        val buttons: MutableList<Pair<String, LinearLayout>> = arrayListOf()
    )

    fun initialize(featureContext: FeatureContext) {
        if (context != null) return
        val clazz = KavaReflector.loadClass("com.tencent.mm.ui.conversation.MainUI", featureContext.hostClassLoader())
            ?: run {
                trace("未找到 com.tencent.mm.ui.conversation.MainUI，标签分组未安装")
                return
            }
        val layout = KavaReflector.findMethodRecursive(clazz, "getLayoutView") ?: run {
            HLog.e("$TAG 未找到 MainUI.getLayoutView，标签分组未安装")
            return
        }
        val resume = KavaReflector.findMethodRecursive(clazz, "onResume")
        val destroy = KavaReflector.findMethodRecursive(clazz, "onDestroy")
        trace("初始化 MainUI Hook: layout=${layout.declaringClass.name}, " +
            "resume=${resume?.declaringClass?.name}, destroy=${destroy?.declaringClass?.name}")
        context = featureContext
        ConversationGroupHomeProjection.setTabFilter { filter }
        HookRegistry.get().hook(layout, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable()) return
                val original = param.result as? View ?: return
                if (original.tag == ROOT_TAG) return
                clearChangedAccount()
                runCatching {
                    hosts[param.thisObject]?.let {
                        param.result = it.root
                        return@runCatching
                    }
                    val host = attach(original) ?: return
                    hosts[param.thisObject] = host
                    param.result = host.root
                    render(host)
                    reload()
                }.onFailure { HLog.e("$TAG 挂载顶栏失败", it) }
            }
        })
        resume?.let { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { clearChangedAccount() }
                override fun afterHookedMethod(param: MethodHookParam) { reload() }
            })
        }
        destroy?.let { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    hosts.remove(param.thisObject)?.let { removeHost(it) }
                    if (hosts.isEmpty()) { filter = null; loadedIcons = emptyMap(); bitmaps.evictAll() }
                }
            })
        }
        listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reload() }.also {
            HchatStorage.preferences(featureContext.hostContext(), ConversationTabsStore.PREFS_NAME)
                .registerOnSharedPreferenceChangeListener(it)
        }
        reload()
    }

    private fun attach(original: View): Host? {
        val root = original as? ViewGroup ?: run {
            HLog.e("$TAG MainUI.getLayoutView 返回的不是 ViewGroup: ${original.javaClass.name}")
            return null
        }
        if (root.findViewWithTag<View>(ROOT_TAG) != null) return null
        val strip = HorizontalScrollView(original.context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
            tag = ROOT_TAG
            id = View.generateViewId()
        }
        val row = LinearLayout(original.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        strip.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val shifted = if (root is RelativeLayout) {
            val children = (0 until root.childCount).map { root.getChildAt(it) }
            children.mapNotNull { child ->
                val params = child.layoutParams as? RelativeLayout.LayoutParams ?: return@mapNotNull null
                if (params.getRule(RelativeLayout.ALIGN_PARENT_TOP) == 0) return@mapNotNull null
                val originalParams = RelativeLayout.LayoutParams(params)
                params.addRule(RelativeLayout.ALIGN_PARENT_TOP, 0)
                params.addRule(RelativeLayout.BELOW, strip.id)
                child.layoutParams = params
                child to originalParams
            }
        } else {
            emptyList()
        }
        val stripParams = if (root is RelativeLayout) {
            RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
            }
        } else {
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(strip, 0, stripParams)
        strip.bringToFront()
        trace("挂载标签栏: root=${root.javaClass.name}, parent=${root.parent?.javaClass?.name}, " +
            "shifted=${shifted.size}")
        return Host(root, strip, row, shifted)
    }

    private fun removeHost(host: Host) {
        host.shifted.forEach { (child, params) -> child.layoutParams = RelativeLayout.LayoutParams(params) }
        (host.strip.parent as? ViewGroup)?.removeView(host.strip)
        host.content.removeAllViews()
        host.buttons.clear()
    }

    private fun trace(message: String) {
        runCatching { XposedBridge.log("$TAG $message") }
    }

    private fun reload() {
        val owner = context ?: return
        val ticket = revision.incrementAndGet()
        worker.execute {
            if (ticket != revision.get()) return@execute
            val nextAccount = ConversationTabsStore.accountKey()
            val next = ConversationTabsStore.load(owner.hostContext())
            val filters = if (next.enabled) next.tabs.associate { it.id to ConversationTabFilter(it) } else emptyMap()
            val images = if (!next.enabled) emptyMap() else buildMap {
                next.tabs.filter { it.display != ConversationTabDisplay.NAME && it.iconPath.isNotBlank() }.forEach { tab ->
                    val bitmap = bitmaps.get(tab.iconPath) ?: ConversationTabsIconStore.loadBitmap(tab.iconPath)
                        ?.also { bitmaps.put(tab.iconPath, it) }
                    if (bitmap != null) put(tab.iconPath, bitmap)
                }
            }
            main.post {
                if (ticket != revision.get() || context !== owner) return@post
                if (account != nextAccount) { selectedId = "all"; account = nextAccount }
                val changed = config != next
                config = next
                loadedIcons = images
                compiledFilters = filters
                if (config.tabs.none { it.id == selectedId }) selectedId = config.tabs.first { it.kind == ConversationTabKind.ALL }.id
                if (config.enabled && !ready) {
                    DexInstallScheduler.schedule(ConversationTabsFeature.ID, "标签分组", DexInstallScheduler.Stage.BRIDGE) {
                        val success = ConversationGroupHomeProjection.installTabs(owner)
                        if (success) main.post {
                            if (context === owner) {
                                ready = true
                                trace("首页查询 Hook 安装成功")
                                publish(); hosts.values.forEach(::render); requestRefresh()
                            }
                        }
                        success
                    }
                }
                publish()
                hosts.values.forEach(::render)
                if (changed) requestRefresh()
                if (!config.enabled) bitmaps.evictAll()
            }
        }
    }

    private fun publish() {
        filter = if (!ready || !config.enabled || account.isBlank() || hosts.isEmpty()) null else
            compiledFilters[selectedId]
    }

    private fun clearChangedAccount() {
        if (account == ConversationTabsStore.accountKey()) return
        filter = null
        selectedId = "all"
        config = ConversationTabsConfig()
        compiledFilters = emptyMap()
        loadedIcons = emptyMap()
        hosts.values.forEach { it.strip.visibility = View.GONE; it.content.removeAllViews(); it.buttons.clear() }
    }

    private fun select(id: String) {
        if (id == selectedId || config.tabs.none { it.id == id }) return
        selectedId = id
        publish()
        hosts.values.forEach(::highlight)
        requestRefresh()
    }

    private fun requestRefresh() {
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 80L)
    }

    private fun render(host: Host) {
        host.strip.visibility = if (config.enabled && account.isNotBlank()) View.VISIBLE else View.GONE
        host.content.removeAllViews()
        host.buttons.clear()
        if (host.strip.visibility != View.VISIBLE) return
        val ctx = host.root.context
        val density = ctx.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + .5f).toInt()
        val dark = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        host.strip.setBackgroundColor(if (dark) Color.rgb(25, 25, 25) else Color.rgb(247, 247, 247))
        config.tabs.forEach { tab ->
            val button = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                minimumWidth = dp(48); minimumHeight = dp(48)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                contentDescription = tab.name
                isFocusable = true
                setOnClickListener { select(tab.id) }
            }
            if (tab.display != ConversationTabDisplay.NAME) {
                val bitmap = loadedIcons[tab.iconPath]
                if (bitmap != null) {
                    button.addView(ImageView(ctx).apply {
                        setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(22), dp(22)))
                } else {
                    button.addView(TextView(ctx).apply { text = tab.icon; textSize = 18f
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
                }
            }
            if (tab.display != ConversationTabDisplay.ICON) {
                button.addView(TextView(ctx).apply {
                    text = tab.name; textSize = 14f; setSingleLine()
                    if (tab.display == ConversationTabDisplay.BOTH) setPadding(dp(6), 0, 0, 0)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
            }
            host.content.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            host.buttons.add(tab.id to button)
        }
        highlight(host)
    }

    private fun highlight(host: Host) {
        val dark = host.root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val density = host.root.resources.displayMetrics.density
        host.buttons.forEach { (id, button) ->
            val selected = id == selectedId
            button.isSelected = selected
            button.background = if (selected) GradientDrawable().apply {
                cornerRadius = 8 * density; setColor(if (dark) 0x3325BA73 else 0x1825BA73)
            } else null
            for (i in 0 until button.childCount) (button.getChildAt(i) as? TextView)?.let {
                it.setTextColor(if (selected) 0xFF07A45E.toInt() else if (dark) Color.LTGRAY else Color.DKGRAY)
                it.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }

    fun destroy(owner: FeatureContext) {
        revision.incrementAndGet()
        listener?.let { HchatStorage.preferences(owner.hostContext(), ConversationTabsStore.PREFS_NAME)
            .unregisterOnSharedPreferenceChangeListener(it) }
        listener = null; context = null; filter = null; ready = false; account = ""
        main.post {
            main.removeCallbacks(refresh)
            hosts.values.toList().forEach(::removeHost)
            hosts.clear(); loadedIcons = emptyMap(); compiledFilters = emptyMap(); bitmaps.evictAll()
            ConversationGroupHomeProjection.refresh()
        }
    }
}
