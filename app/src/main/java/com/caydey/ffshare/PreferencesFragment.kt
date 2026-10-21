package com.caydey.ffshare

import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.*
import com.caydey.ffshare.utils.Settings

class PreferencesFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        dynamicallyShowCustomName()
        dynamicallyAddCustomParamTooltips()
        setupAnimePreset()
        setupAnimePresetUndo()
    }
    private fun dynamicallyAddCustomParamTooltips() {
        val customParamKeys = arrayOf("pref_custom_video_params", "pref_custom_audio_params", "pref_custom_image_params")
        for (customParamKey in customParamKeys) {
            val element = findPreference<EditTextPreference>(customParamKey)
            element?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        }
    }
    private fun dynamicallyShowCustomName() {
        // only show pref_compressed_media_custom_name if pref_compressed_media_name is "Custom"
        val customMediaNamePreference = findPreference<EditTextPreference>("pref_compressed_media_custom_name")
        val compressedMediaNamePreference = findPreference<ListPreference>("pref_compressed_media_name")
        compressedMediaNamePreference?.setOnPreferenceChangeListener { _, value ->
            customMediaNamePreference?.isVisible = (value == "CUSTOM")
            true
        }
        // trigger update for initial load
        compressedMediaNamePreference?.callChangeListener(compressedMediaNamePreference.value)
    }
    // one tap sets everything for small AV1 files from anime/cartoon DVDs
    private fun setupAnimePreset() {
        findPreference<Preference>("pref_apply_anime_preset")?.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_anime_preset)
                .setMessage(getString(R.string.settings_anime_preset_confirm) + runningConversionHint())
                .setPositiveButton(R.string.settings_anime_preset_apply) { _, _ ->
                    Settings(requireContext()).applyAnimePreset()
                    Toast.makeText(requireContext(), R.string.settings_anime_preset_applied, Toast.LENGTH_SHORT).show()
                    // reload the screen so every list shows its new value
                    activity?.recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }
    // brings back the settings from before the preset, or the app defaults if none were saved;
    // the dialog lists every setting that changes (old -> new)
    private fun setupAnimePresetUndo() {
        val undo = findPreference<Preference>("pref_undo_anime_preset") ?: return
        val savedAt = Settings(requireContext()).animePresetBackupTime
        undo.summary = if (savedAt != null) getString(R.string.settings_anime_preset_undo_summary, formatTime(savedAt))
                       else getString(R.string.settings_anime_preset_undo_summary_defaults)
        undo.setOnPreferenceClickListener {
            val settings = Settings(requireContext())
            val changes = settings.animePresetUndoChanges()
            if (changes.isEmpty()) {
                Toast.makeText(requireContext(), R.string.settings_anime_preset_undo_nothing, Toast.LENGTH_SHORT).show()
            } else {
                val backupTime = settings.animePresetBackupTime
                val intro = if (backupTime != null) getString(R.string.settings_anime_preset_undo_confirm, formatTime(backupTime))
                            else getString(R.string.settings_anime_preset_undo_confirm_defaults)
                val list = changes.joinToString("\n") { "• " + describeChange(it) }
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.settings_anime_preset_undo)
                    .setMessage(intro + "\n\n" + list + runningConversionHint())
                    .setPositiveButton(R.string.settings_anime_preset_undo_apply) { _, _ ->
                        settings.undoAnimePreset()
                        Toast.makeText(requireContext(), R.string.settings_anime_preset_undone, Toast.LENGTH_SHORT).show()
                        // reload the screen so every list shows its new value
                        activity?.recreate()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            true
        }
    }
    // a batch reads the settings file by file, so waiting files would pick up the change
    private fun runningConversionHint(): String =
        if (EncodeService.cancelHook != null) "\n\n" + getString(R.string.settings_preset_running_hint) else ""

    // "Title: old value → new value", values as the lists and switches show them
    private fun describeChange(change: Settings.PresetChange): String {
        val preference = findPreference<Preference>(change.key)
        val title = preference?.title ?: change.key
        return "$title: ${describeValue(preference, change.now)} → ${describeValue(preference, change.after)}"
    }
    private fun describeValue(preference: Preference?, value: Any?): String = when {
        preference is ListPreference -> {
            val index = preference.findIndexOfValue(value as? String)
            if (index >= 0) preference.entries[index].toString() else value?.toString() ?: "–"
        }
        preference is TwoStatePreference ->
            getString(if (value == true) R.string.settings_value_on else R.string.settings_value_off)
        value == null || value == "" -> getString(R.string.settings_value_empty)
        else -> value.toString()
    }
    private fun formatTime(millis: Long): String = DateUtils.formatDateTime(requireContext(), millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME)
}
