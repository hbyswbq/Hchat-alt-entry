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
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.conversationgroup.ConversationGroupHomeProjection
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A measured child of MainUI's native root; no row hooks or per-frame listeners. */
internal object ConversationTabsRuntime {
    private const val TAG = "[Hchat:ConversationTabs]"
    private const val ROOT_TAG = "hchat:conversation-tabs-root"
    private const val HOST_TAG_ID = 0x48435401
    private const val MAIN_VIEW_CLASS = "com.tencent.mm.ui.conversation.MainUIView"
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1),
        { task -> Thread(task, "Hchat-ConversationTabs").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy())
    private val reloadLock = Any()
    private val revision = AtomicLong()
    private val lifecycle = AtomicLong()
    private var pendingDelivery: Runnable? = null
    // Host 由标签栏自身持有；全局索引不能经 View/Activity 反向保活 MainUI。
    private val hosts = WeakHashMap<Any, WeakReference<Host>>()
    private val bitmaps = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    @Volatile private var context: FeatureContext? = null
    private var hookedMainUi: Class<*>? = null
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
        if (!installUiHooks(featureContext.hostClassLoader())) return
        synchronized(reloadLock) {
            lifecycle.incrementAndGet()
            context = featureContext
        }
        ConversationGroupHomeProjection.setTabFilter { filter }
        listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reload() }.also {
            HchatStorage.preferences(featureContext.hostContext(), ConversationTabsStore.PREFS_NAME)
                .registerOnSharedPreferenceChangeListener(it)
        }
        // Hosts created before the DexKit-backed feature initialization are rendered here too.
        reload()
    }

    /** Register before LauncherUI is created; this path does not need DexKit or account state. */
    @Synchronized
    fun installUiHooks(classLoader: ClassLoader): Boolean {
        val clazz = KavaReflector.loadClass("com.tencent.mm.ui.conversation.MainUI", classLoader)
            ?: run {
                HLog.e("$TAG 未找到 com.tencent.mm.ui.conversation.MainUI，标签分组未安装")
                return false
            }
        if (hookedMainUi == clazz) return true
        val layout = KavaReflector.findMethodRecursive(clazz, "getLayoutView") ?: run {
            HLog.e("$TAG 未找到 MainUI.getLayoutView，标签分组未安装")
            return false
        }
        val resume = KavaReflector.findMethodRecursive(clazz, "onResume") ?: return false
        val destroy = KavaReflector.findMethodRecursive(clazz, "onDestroy") ?: return false
        val rootField = KavaReflector.declaredFields(clazz).singleOrNull {
            !KavaReflector.isStatic(it) && it.type.name == MAIN_VIEW_CLASS
        } ?: run {
            HLog.e("$TAG 未找到 MainUI 的唯一 MainUIView 字段，标签分组未安装")
            return false
        }
        val hooks = arrayListOf<XC_MethodHook.Unhook>()
        return runCatching {
            hooks += HookRegistry.get().hook(layout, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable() || !clazz.isInstance(param.thisObject)) return
                    val original = param.result as? View ?: return
                    clearChangedAccount()
                    ensureHost(param.thisObject, original)
                    reload()
                }
            })
            hooks += HookRegistry.get().hook(resume, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable() || !clazz.isInstance(param.thisObject)) return
                    clearChangedAccount()
                    // getLayoutView inflates a NEW root. Read the existing native root instead.
                    val root = KavaReflector.readField(rootField, param.thisObject) as? View
                    if (root != null) ensureHost(param.thisObject, root)
                    reload()
                }
            })
            hooks += HookRegistry.get().hook(destroy, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!clazz.isInstance(param.thisObject)) return
                    hosts.remove(param.thisObject)?.get()?.let { removeHost(it) }
                    if (liveHosts().isEmpty()) synchronized(reloadLock) {
                        invalidateLoads()
                        filter = null; loadedIcons = emptyMap(); compiledFilters = emptyMap(); bitmaps.evictAll()
                    }
                }
            })
            hookedMainUi = clazz
            true
        }.getOrElse {
            hooks.forEach { hook -> HookRegistry.get().unhook(hook) }
            HLog.e("$TAG 安装 MainUI 生命周期失败", it)
            false
        }
    }

    private fun ensureHost(owner: Any, root: View) {
        runCatching {
            val previous = hosts[owner]?.get()
            if (previous != null && previous.root === root && previous.strip.parent === root) return
            if (previous != null) {
                hosts.remove(owner)
                removeHost(previous)
            }
            val host = attach(root) ?: return
            hosts[owner] = WeakReference(host)
            render(host)
        }.onFailure { HLog.e("$TAG 挂载顶栏失败", it) }
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
        return Host(root, strip, row, shifted).also { strip.setTag(HOST_TAG_ID, it) }
    }

    private fun liveHosts(): List<Host> {
        val current = arrayListOf<Host>()
        val entries = hosts.entries.iterator()
        while (entries.hasNext()) {
            val host = entries.next().value.get()
            if (host == null) entries.remove() else current.add(host)
        }
        return current
    }

    private fun removeHost(host: Host) {
        host.strip.setTag(HOST_TAG_ID, null)
        host.shifted.forEach { (child, params) -> child.layoutParams = RelativeLayout.LayoutParams(params) }
        (host.strip.parent as? ViewGroup)?.removeView(host.strip)
        host.content.removeAllViews()
        host.buttons.clear()
    }

    // 调用方持有 reloadLock；后台队列和主线程待交付结果都只保留最新一份。
    private fun invalidateLoads() {
        revision.incrementAndGet()
        worker.queue.clear()
        pendingDelivery?.let(main::removeCallbacks)
        pendingDelivery = null
    }

    private fun reload() = synchronized(reloadLock) {
        val owner = context ?: return@synchronized
        invalidateLoads()
        val ticket = revision.get()
        val generation = lifecycle.get()
        worker.execute {
            if (ticket != revision.get()) return@execute
            runCatching {
                val nextAccount = ConversationTabsStore.accountKey()
                val next = ConversationTabsStore.load(owner.hostContext())
                if (ticket != revision.get()) return@runCatching
                val filters = if (next.enabled) next.tabs.associate { it.id to ConversationTabFilter(it) } else emptyMap()
                val images = if (!next.enabled) emptyMap() else buildMap {
                    for (tab in next.tabs) {
                        if (ticket != revision.get()) return@runCatching
                        if (tab.display == ConversationTabDisplay.NAME || tab.iconPath.isBlank()) continue
                        val bitmap = bitmaps.get(tab.iconPath) ?: ConversationTabsIconStore.loadBitmap(tab.iconPath)
                        if (bitmap != null) {
                            synchronized(reloadLock) {
                                if (ticket != revision.get()) return@runCatching
                                bitmaps.put(tab.iconPath, bitmap)
                            }
                            put(tab.iconPath, bitmap)
                        }
                    }
                }
                synchronized(reloadLock) {
                    if (ticket != revision.get() || context !== owner) return@synchronized
                    val delivery = Runnable {
                        synchronized(reloadLock) {
                            if (ticket != revision.get() || context !== owner) return@synchronized
                            pendingDelivery = null
                            if (account != nextAccount) { selectedId = "all"; account = nextAccount }
                            val changed = config != next
                            config = next
                            loadedIcons = images
                            compiledFilters = filters
                            if (config.tabs.none { it.id == selectedId }) selectedId = config.tabs.first { it.kind == ConversationTabKind.ALL }.id
                            if (config.enabled && !ready) {
                                DexInstallScheduler.schedule(ConversationTabsFeature.ID, "标签分组", DexInstallScheduler.Stage.BRIDGE) {
                                    if (context !== owner || lifecycle.get() != generation) return@schedule false
                                    val success = ConversationGroupHomeProjection.installTabs(owner)
                                    if (success) main.post {
                                        synchronized(reloadLock) {
                                            if (context === owner && lifecycle.get() == generation) {
                                                ready = true
                                                publish(); liveHosts().forEach(::render); requestRefresh()
                                            }
                                        }
                                    }
                                    success
                                }
                            }
                            publish()
                            liveHosts().forEach(::render)
                            if (changed) requestRefresh()
                            if (!config.enabled) bitmaps.evictAll()
                        }
                    }
                    pendingDelivery?.let(main::removeCallbacks)
                    pendingDelivery = delivery
                    main.post(delivery)
                }
            }.onFailure { HLog.e("$TAG 读取标签配置失败", it) }
        }
    }

    private fun publish() {
        filter = if (!ready || !config.enabled || account.isBlank() || liveHosts().isEmpty()) null else
            compiledFilters[selectedId]
    }

    private fun clearChangedAccount() {
        if (account == ConversationTabsStore.accountKey()) return
        filter = null
        selectedId = "all"
        config = ConversationTabsConfig()
        compiledFilters = emptyMap()
        loadedIcons = emptyMap()
        liveHosts().forEach { it.strip.visibility = View.GONE; it.content.removeAllViews(); it.buttons.clear() }
    }

    private fun select(id: String) {
        if (id == selectedId || config.tabs.none { it.id == id }) return
        selectedId = id
        publish()
        liveHosts().forEach(::highlight)
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
        val generation = synchronized(reloadLock) {
            if (context !== owner) return
            ConversationTabsIconPicker.cancelAll()
            invalidateLoads()
            listener?.let { HchatStorage.preferences(owner.hostContext(), ConversationTabsStore.PREFS_NAME)
                .unregisterOnSharedPreferenceChangeListener(it) }
            listener = null; context = null; filter = null; ready = false; account = ""
            loadedIcons = emptyMap(); compiledFilters = emptyMap(); bitmaps.evictAll()
            hookedMainUi = null
            lifecycle.incrementAndGet()
        }
        main.post {
            synchronized(reloadLock) {
                if (lifecycle.get() != generation || context != null) return@synchronized
                main.removeCallbacks(refresh)
                liveHosts().forEach(::removeHost)
                hosts.clear()
                ConversationGroupHomeProjection.refresh()
            }
        }
    }
}
