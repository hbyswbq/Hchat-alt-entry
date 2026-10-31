package h.Hchat.hooks.items.moments

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentsUploadTailPresetsRegressionTest {
    private val preferences = TestPreferences()

    @Test
    fun `offline source contains all extracted pairs and exposes filtered unique names`() {
        assertEquals(569, MomentsUploadTailPresets.sourceEntries.size)
        assertEquals(555, MomentsUploadTailPresets.options.size)
        assertTrue(MomentsUploadTailPresets.sourceEntries.all { it.appId.isNotBlank() })
        assertEquals(569, MomentsUploadTailPresets.sourceEntries.map { it.appId }.toSet().size)
        assertEquals(568, MomentsUploadTailPresets.sourceEntries.count { it.name.isNotBlank() })
        assertTrue(MomentsUploadTailPresets.options.all { it.name.isNotBlank() })
        assertEquals(
            MomentsUploadTailPresets.options.size,
            MomentsUploadTailPresets.options.map { it.name }.toSet().size
        )
        assertFalse(MomentsUploadTailPresets.sourceEntries.all { it.name.isNotBlank() })
    }

    @Test
    fun `duplicate names keep the first source mapping`() {
        val firstByName = MomentsUploadTailPresets.sourceEntries
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name }
        assertEquals(firstByName, MomentsUploadTailPresets.options)
        val duplicate = MomentsUploadTailPresets.sourceEntries
            .filter { it.name.isNotBlank() }
            .groupBy { it.name }
            .values.first { it.size > 1 }
        assertEquals(duplicate.first(), MomentsUploadTailPresets.options.first { it.name == duplicate.first().name })
    }

    @Test
    fun `selection saves both existing fields and restored values resolve exact preset`() {
        preferences.edit().clear().putBoolean(MomentsUploadTailSettings.KEY_ENABLE, true).commit()
        val selected = MomentsUploadTailPresets.options.first()

        MomentsUploadTailPresets.saveSelection(preferences, selected)

        val reopenedId = preferences.getString(MomentsUploadTailSettings.KEY_SDK_ID, "").orEmpty()
        val reopenedName = preferences.getString(MomentsUploadTailSettings.KEY_SDK_APP_NAME, "").orEmpty()
        assertEquals(selected.appId, reopenedId)
        assertEquals(selected.name, reopenedName)
        assertEquals(selected, MomentsUploadTailPresets.matchingPreset(reopenedId, reopenedName))
        assertTrue(preferences.getBoolean(MomentsUploadTailSettings.KEY_ENABLE, false))
    }

    @Test
    fun `manual edits remain saved and no longer match a preset`() {
        val preset = MomentsUploadTailPresets.options.first()
        MomentsUploadTailPresets.saveSelection(preferences, preset)
        val editedId = "manual-sdk-id"
        val editedName = "手动修改名称"
        preferences.edit().putString(MomentsUploadTailSettings.KEY_SDK_ID, editedId).commit()
        preferences.edit().putString(MomentsUploadTailSettings.KEY_SDK_APP_NAME, editedName).commit()

        assertEquals(editedId, preferences.getString(MomentsUploadTailSettings.KEY_SDK_ID, null))
        assertEquals(editedName, preferences.getString(MomentsUploadTailSettings.KEY_SDK_APP_NAME, null))
        assertNull(
            MomentsUploadTailPresets.matchingPreset(
                preferences.getString(MomentsUploadTailSettings.KEY_SDK_ID, "").orEmpty(),
                preferences.getString(MomentsUploadTailSettings.KEY_SDK_APP_NAME, "").orEmpty()
            )
        )
    }

    private class TestPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = TestEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class TestEditor : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private var clear = false
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { updates[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { updates[key!!] = values }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { updates[key!!] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { updates[key!!] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { updates[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { updates[key!!] = value }
            override fun remove(key: String?): SharedPreferences.Editor = apply { updates[key!!] = Removed }
            override fun clear(): SharedPreferences.Editor = apply { clear = true }
            override fun commit(): Boolean = applyChanges().let { true }
            override fun apply() { applyChanges() }
            private fun applyChanges() {
                if (clear) values.clear()
                updates.forEach { (key, value) -> if (value === Removed) values.remove(key) else values[key] = value }
            }
        }

        private object Removed
    }
}
