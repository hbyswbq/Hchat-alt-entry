package h.Hchat.hooks.items.conversationtabs

import android.view.View
import android.widget.RelativeLayout
import com.tencent.mm.ui.conversation.MainUI
import com.tencent.mm.ui.conversation.MainUIView
import com.tencent.mm.ui.conversation.OtherUi
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector

private var checks = 0
private var scenarios = 0
private fun expect(condition: Boolean, message: String) {
    checks++
    check(condition) { message }
}
private fun scenario(name: String, block: () -> Unit) {
    ConversationTabsRuntime.reset()
    block()
    scenarios++
    println("PASS: $name")
}
private val registry get() = HookRegistry.get()
private val loader get() = MainUI::class.java.classLoader!!
private fun install() = ConversationTabsRuntime.installUiHooks(loader)
private fun invoke(owner: Any, name: String): Any? =
    registry.invoke(owner, KavaReflector.findMethodRecursive(owner.javaClass, name)!!)
private fun MainUIView.listParams() = list.layoutParams as RelativeLayout.LayoutParams

fun main() {
    scenario("layout before complete feature initialization is retained and rendered afterwards") {
        expect(install(), "Early lifecycle hooks must install without FeatureContext")
        val owner = MainUI()
        val root = invoke(owner, "getLayoutView") as MainUIView
        val strip = ConversationTabsRuntime.strip(owner)!!
        expect(ConversationTabsRuntime.root(owner) === root, "Early root must be retained")
        expect(root.childCount == 2, "Attach exactly one strip alongside native content")
        expect(strip.visibility == View.GONE, "Early host waits for configuration")
        expect(ConversationTabsRuntime.reloads == 0, "No configuration I/O before full initialization")
        ConversationTabsRuntime.initialize(FeatureContext())
        expect(strip.visibility == View.VISIBLE, "Full initialization must render the retained early host")
        expect(ConversationTabsRuntime.strip(owner) === strip, "Initialization must reuse the early strip")
        expect(registry.count == 3, "Full initialization must not install duplicate lifecycle hooks")
        expect(HchatStorage.prefs.registrations == 1, "Register configuration listener once")
    }
    scenario("resume recovers a root inflated before hooks without reinflating the page") {
        val owner = MainUI()
        val existing = owner.getLayoutView() as MainUIView
        expect(install(), "Install hooks after initial layout")
        invoke(owner, "onResume")
        expect(ConversationTabsRuntime.root(owner) === existing, "Resume must attach to the existing native field")
        expect(owner.inflationCount == 1, "Resume must never call getLayoutView to create a detached root")
        expect(existing.childCount == 2, "Existing root receives one strip")
    }
    scenario("repeated resumes and initialization do not duplicate hooks or view children") {
        val owner = MainUI()
        val existing = owner.getLayoutView() as MainUIView
        ConversationTabsRuntime.initialize(FeatureContext())
        invoke(owner, "onResume")
        val strip = ConversationTabsRuntime.strip(owner)
        val additions = existing.additions
        repeat(5) {
            expect(install(), "Repeated installation remains successful")
            ConversationTabsRuntime.initialize(FeatureContext())
            invoke(owner, "onResume")
        }
        expect(ConversationTabsRuntime.strip(owner) === strip, "Keep the same strip on repeated resume")
        expect(existing.additions == additions, "Do not add/remove/recreate on repeated resume")
        expect(existing.childCount == 2 && registry.count == 3, "No duplicate children or hooks")
        expect(HchatStorage.prefs.registrations == 1, "No duplicate configuration listener")
        expect(owner.inflationCount == 1, "Repeated resume must not inflate views")
    }
    scenario("layout rebuild keeps the new native return value and restores the old root") {
        install()
        val owner = MainUI()
        val previous = invoke(owner, "getLayoutView") as MainUIView
        val oldStrip = ConversationTabsRuntime.strip(owner)!!
        val current = invoke(owner, "getLayoutView") as MainUIView
        expect(current !== previous && current === owner.nativeRoot, "Never return a cached previous root")
        expect(ConversationTabsRuntime.root(owner) === current, "Host must track the replacement root")
        expect(oldStrip.parent == null && previous.childCount == 1, "Remove the strip from the old root")
        expect(previous.listParams().getRule(RelativeLayout.ALIGN_PARENT_TOP) == -1, "Restore native top alignment")
        expect(previous.listParams().getRule(RelativeLayout.BELOW) == 0, "Remove obsolete strip anchor")
        expect(current.listParams().getRule(RelativeLayout.BELOW) == ConversationTabsRuntime.strip(owner)!!.id,
            "Replacement list must anchor below its own strip")
    }
    scenario("resume repairs an externally removed strip without losing native layout rules") {
        install()
        val owner = MainUI()
        val root = invoke(owner, "getLayoutView") as MainUIView
        val oldStrip = ConversationTabsRuntime.strip(owner)!!
        root.removeView(oldStrip)
        invoke(owner, "onResume")
        val newStrip = ConversationTabsRuntime.strip(owner)!!
        expect(newStrip !== oldStrip && newStrip.parent === root, "Reattach after host removes the strip")
        expect(root.childCount == 2, "Repair must add only one strip")
        expect(root.listParams().getRule(RelativeLayout.BELOW) == newStrip.id, "Update the native list anchor")
        invoke(owner, "onDestroy")
        expect(root.listParams().getRule(RelativeLayout.ALIGN_PARENT_TOP) == -1, "Destroy restores original top rule")
        expect(root.listParams().getRule(RelativeLayout.BELOW) == 0, "Repair must not snapshot a stale anchor")
    }
    scenario("destroy removes only its owner's host and permits a fresh page") {
        install()
        val first = MainUI()
        val second = MainUI()
        val firstRoot = invoke(first, "getLayoutView") as MainUIView
        val secondRoot = invoke(second, "getLayoutView") as MainUIView
        invoke(first, "onDestroy")
        expect(ConversationTabsRuntime.hostCount() == 1, "Destroy only the matching host")
        expect(firstRoot.childCount == 1 && secondRoot.childCount == 2, "Other live root remains attached")
        invoke(second, "onDestroy")
        expect(ConversationTabsRuntime.hostCount() == 0 && secondRoot.childCount == 1, "Last destroy clears hosts")
        val fresh = MainUI()
        invoke(fresh, "getLayoutView")
        expect(ConversationTabsRuntime.hostCount() == 1, "Lifecycle hooks remain usable for another page")
    }
    scenario("inherited lifecycle callbacks ignore unrelated pages and missing roots") {
        install()
        invoke(OtherUi(), "onResume")
        invoke(OtherUi(), "onDestroy")
        invoke(MainUI(), "onResume")
        expect(ConversationTabsRuntime.hostCount() == 0, "Only MainUI instances with an existing root can attach")
        val owner = MainUI()
        registry.dispatch(owner, KavaReflector.findMethodRecursive(MainUI::class.java, "getLayoutView")!!,
            MainUIView(), IllegalStateException("Native layout failed"))
        expect(ConversationTabsRuntime.hostCount() == 0, "Do not attach when native layout throws")
    }
    scenario("partially failed hook installation is rolled back and retried") {
        registry.failOn = "onResume"
        expect(!install(), "Report partial installation failure")
        expect(registry.count == 0 && registry.unhooks == 1, "Unhook the already installed layout callback")
        expect(HLog.errors.size == 1, "Keep the installation failure observable")
        registry.failOn = null
        expect(install(), "A subsequent installation can retry")
        expect(registry.count == 3, "Retry produces exactly one hook per lifecycle event")
        val owner = MainUI()
        invoke(owner, "getLayoutView")
        expect(ConversationTabsRuntime.hostCount() == 1, "Retried hooks attach successfully")
    }
    println("Conversation tabs lifecycle: $scenarios scenarios, $checks checks, 0 failures")
}
