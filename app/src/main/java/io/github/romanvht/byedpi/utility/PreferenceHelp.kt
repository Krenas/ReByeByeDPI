package io.github.romanvht.byedpi.utility

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference
import io.github.romanvht.byedpi.R

/**
 * Keeps a plain-language explanation visible next to each setting and appends
 * the currently saved value. It never writes preferences.
 */
object PreferenceHelp {
    fun apply(
        fragment: PreferenceFragmentCompat,
        descriptions: Map<String, Int>,
        inputLimits: Map<String, String> = emptyMap()
    ) {
        descriptions.forEach { (key, descriptionId) ->
            val preference = fragment.findPreference<Preference>(key) ?: return@forEach
            val summaryLines = mutableListOf(fragment.getString(descriptionId))
            inputLimits[key]?.let { limit ->
                summaryLines += fragment.getString(R.string.help_input_limit, limit)
            }
            currentValue(fragment, preference)?.let { value ->
                summaryLines += fragment.getString(R.string.help_current_value, value)
            }
            preference.summary = summaryLines.joinToString("\n")
        }
    }

    fun applySingle(
        fragment: PreferenceFragmentCompat,
        key: String,
        descriptionId: Int
    ) {
        apply(fragment, mapOf(key to descriptionId))
    }

    private fun currentValue(
        fragment: PreferenceFragmentCompat,
        preference: Preference
    ): String? = when (preference) {
        is EditTextPreference -> {
            val value = preference.text?.trim().orEmpty()
            when {
                value.isBlank() -> fragment.getString(R.string.help_value_not_set)
                value.length > 72 -> value.take(69) + "…"
                else -> value
            }
        }
        is ListPreference -> preference.entry?.toString()
            ?: preference.value
            ?: fragment.getString(R.string.help_value_not_set)
        is TwoStatePreference -> fragment.getString(
            if (preference.isChecked) R.string.help_enabled else R.string.help_disabled
        )
        else -> null
    }
}
