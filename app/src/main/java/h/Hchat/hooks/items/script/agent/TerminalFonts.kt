package h.Hchat.hooks.items.script.agent

import android.content.Context
import android.graphics.Typeface
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// 终端显示字体：字体文件随模块打进 assets/fonts，运行时从模块 APK 解到 Hchat/fonts 再加载
object TerminalFonts {

    // 默认跟随系统等宽字体，与加字体功能之前的表现一致
    const val DEFAULT_ID = "system"

    private const val PREFS_NAME = "Hchat_terminal_config"
    private const val KEY_FONT_ID = "terminal_font_id"

    data class Font(val id: String, val label: String, val asset: String)

    // 顺序即设置页与终端菜单里的显示顺序，第一项是默认值
    val ALL = listOf(
        Font(DEFAULT_ID, "系统默认", ""),
        Font("jetbrains_mono", "JetBrains Mono", "fonts/JetBrainsMono-Regular.ttf"),
        Font("source_code_pro", "Source Code Pro", "fonts/SourceCodePro-Regular.ttf"),
        Font("ubuntu_mono", "Ubuntu Mono", "fonts/UbuntuMono-Regular.ttf"),
    )

    private val cache = ConcurrentHashMap<String, Typeface>()

    fun byId(id: String?): Font = ALL.firstOrNull { it.id == id } ?: ALL.first()

    fun selectedId(context: Context): String {
        val saved = runCatching {
            HchatStorage.preferences(context, PREFS_NAME).getString(KEY_FONT_ID, DEFAULT_ID)
        }.getOrNull()
        return if (ALL.any { it.id == saved }) saved!! else DEFAULT_ID
    }

    fun select(context: Context, id: String) {
        val target = byId(id).id
        runCatching {
            HchatStorage.preferences(context, PREFS_NAME).edit()
                .putString(KEY_FONT_ID, target).apply()
        }.onFailure { HLog.e("[Hchat:Term] 保存终端字体失败: ${it.message}", it) }
    }

    // 取字体对象；任何一步失败都回退系统等宽字体（字体不对可以忍，终端打不开不行）
    fun typeface(context: Context, id: String? = null): Typeface {
        val font = byId(id ?: selectedId(context))
        if (font.asset.isEmpty()) return Typeface.MONOSPACE
        cache[font.id]?.let { return it }
        val loaded = fontFile(context, font)?.let { file ->
            runCatching { Typeface.createFromFile(file) }
                .onFailure { HLog.e("[Hchat:Term] 加载字体 ${font.id} 失败: ${it.message}", it) }
                .getOrNull()
        }
        if (loaded == null) return Typeface.MONOSPACE
        cache[font.id] = loaded
        return loaded
    }

    private fun fontFile(context: Context, font: Font): File? {
        if (font.asset.isEmpty()) return null
        val apk = ProotEnvironment.moduleApkPath() ?: return null
        val name = font.asset.substringAfterLast('/')
        val dir = File(HchatStorage.storageDir(context), "fonts").apply { mkdirs() }
        return runCatching {
            java.util.zip.ZipFile(apk).use { zip ->
                val entry = zip.getEntry("assets/${font.asset}") ?: return null
                val target = File(dir, "$name-${entry.size}")
                if (target.length() == entry.size) return target
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                // 模块更新后字体条目大小变了会换成新文件名，顺手清掉旧副本
                dir.listFiles()?.forEach { old ->
                    if (old.name != target.name && old.name.startsWith("$name-")) old.delete()
                }
                target.takeIf { it.length() > 0L }
            }
        }.onFailure { HLog.e("[Hchat:Term] 解出字体 ${font.id} 失败: ${it.message}", it) }.getOrNull()
    }
}
