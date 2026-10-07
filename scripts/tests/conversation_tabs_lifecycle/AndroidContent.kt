package android.content

open class Context
interface SharedPreferences {
    fun interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?)
    }
    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)
}
