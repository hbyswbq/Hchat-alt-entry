package h.Hchat.hooks.items.conversationtabs

import android.app.Activity
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.KavaReflector
import java.util.concurrent.Executors
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object ConversationTabsIconPicker {
    private data class Pending(
        val activity: WeakReference<Activity>,
        val tabKey: String,
        val callback: (ConversationTabsIconPickResult) -> Unit,
        val processing: AtomicBoolean = AtomicBoolean(false)
    )

    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "Hchat-TabIcon").apply { isDaemon = true } }

    private val nextRequestCode = AtomicInteger(REQUEST_CODE_START)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val resultHookedClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val destroyHookedClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    @JvmStatic
    fun launch(
        activity: Activity,
        tabKey: String,
        callback: (ConversationTabsIconPickResult) -> Unit
    ) {
        val key = tabKey.trim()
        if (!ConversationTabsIconStore.isSupportedTabKey(key)) {
            callback(ConversationTabsIconPickResult.FAILED)
            return
        }
        hookActivityHierarchy(activity.javaClass)
        val requestCode = allocateRequestCode()
        pending[requestCode] = Pending(WeakReference(activity), key, callback)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivityForResult(intent, requestCode) }
            .onFailure {
                val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    activity.startActivityForResult(
                        Intent.createChooser(fallback, "选择标签图标"),
                        requestCode
                    )
                }.onFailure {
                    pending.remove(requestCode)?.callback?.invoke(
                        ConversationTabsIconPickResult.FAILED
                    )
                }
            }
    }

    private fun hookActivityHierarchy(activityClass: Class<*>) {
        var current: Class<*>? = activityClass
        while (current != null && Activity::class.java.isAssignableFrom(current)) {
            hookActivityResult(current)
            hookActivityDestroy(current)
            current = current.superclass
        }
    }

    private fun allocateRequestCode(): Int {
        repeat(REQUEST_CODE_END - REQUEST_CODE_START + 1) {
            val candidate = nextRequestCode.updateAndGet { current ->
                if (current >= REQUEST_CODE_END) REQUEST_CODE_START else current + 1
            }
            if (!pending.containsKey(candidate)) return candidate
        }
        val reused = pending.keys.minOrNull() ?: REQUEST_CODE_START
        pending.remove(reused)?.callback?.invoke(ConversationTabsIconPickResult.CANCELLED)
        return reused
    }

    @Synchronized
    private fun hookActivityResult(clazz: Class<*>) {
        if (!resultHookedClasses.add(clazz)) return
        runCatching {
            val method = KavaReflector.findDeclaredMethod(clazz, "onActivityResult", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Intent::class.java) ?: return
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requestCode = param.args.getOrNull(0) as? Int ?: return
                    val request = pending[requestCode] ?: return
                    val activity = request.activity.get()
                    if (activity == null) {
                        pending.remove(requestCode, request)
                        return
                    }
                    if (param.thisObject !== activity) return
                    val resultCode = param.args.getOrNull(1) as? Int ?: return
                    val data = param.args.getOrNull(2) as? Intent
                    val uri = data?.data
                    if (resultCode != Activity.RESULT_OK || uri == null) {
                        if (pending.remove(requestCode, request)) {
                            request.callback(ConversationTabsIconPickResult.CANCELLED)
                        }
                        return
                    }
                    if (!request.processing.compareAndSet(false, true)) return
                    val appContext = activity.applicationContext
                    worker.execute {
                        val path = runCatching {
                            ConversationTabsIconStore.saveFromUri(appContext, request.tabKey, uri)
                        }.getOrNull()
                        val owner = request.activity.get()
                        if (owner == null) {
                            pending.remove(requestCode, request)
                            ConversationTabsIconStore.delete(appContext, path)
                            return@execute
                        }
                        owner.runOnUiThread {
                            if (!pending.remove(requestCode, request) ||
                                owner.isFinishing || owner.isDestroyed
                            ) {
                                ConversationTabsIconStore.delete(appContext, path)
                                return@runOnUiThread
                            }
                            request.callback(
                                path?.let(ConversationTabsIconPickResult::Saved)
                                    ?: ConversationTabsIconPickResult.FAILED
                            )
                        }
                    }
                }
            })
        }.onFailure { resultHookedClasses.remove(clazz) }
    }

    @Synchronized
    private fun hookActivityDestroy(clazz: Class<*>) {
        if (!destroyHookedClasses.add(clazz)) return
        runCatching {
            val method = KavaReflector.findDeclaredMethod(clazz, "onDestroy") ?: return
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    pending.entries.forEach { entry ->
                        val owner = entry.value.activity.get()
                        if (owner == null || owner === activity) {
                            pending.remove(entry.key, entry.value)
                        }
                    }
                }
            })
        }.onFailure { destroyHookedClasses.remove(clazz) }
    }

    private const val REQUEST_CODE_START = 0x7a10
    private const val REQUEST_CODE_END = 0x7aff
}

sealed class ConversationTabsIconPickResult {
    data class Saved(val path: String) : ConversationTabsIconPickResult()
    object CANCELLED : ConversationTabsIconPickResult()
    object FAILED : ConversationTabsIconPickResult()
}
