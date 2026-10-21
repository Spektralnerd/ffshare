package com.caydey.ffshare.utils

import android.content.Context
import android.content.SharedPreferences

import androidx.preference.PreferenceManager
import com.caydey.ffshare.R
import org.json.JSONException
import org.json.JSONObject

class Settings(private val context: Context) {
    enum class CompressedMediaNameOpts {
        ORIGINAL,
        UUID,
        CUSTOM
    }

    enum class VideoCodecOpts(val raw: String) {
        COPY("copy"),
        DEFAULT(""),
        H264("libx264"),
        H265("libx265"),
        VP9("libvpx-vp9"),
        MPEG4("mpeg4"),
        AV1("av1"),                   // hardware (MediaCodec) when the device has an AV1 encoder, else libaom
        AV1_SOFTWARE("libaom-av1");   // always software (slow, works on every device)
        companion object {
            fun parseCodec(codec: String?): VideoCodecOpts {
                return when (codec) {
                    "h264" -> H264
                    "hevc" -> H265
                    "vp9" -> VP9
                    "mpeg4" -> MPEG4
                    "av1" -> AV1
                    else -> DEFAULT
                }
            }
        }
    }

    enum class AudioCodecOpts(val raw: String) {
        COPY("copy"),
        DEFAULT(""),
        AAC("aac"),
        AC3("ac3"),
        OPUS("libopus"),
        FLAC("flac");
        companion object {
            fun parseCodec(codec: String?): AudioCodecOpts {
                return when (codec) {
                    "aac" -> AAC
                    "ac3" -> AC3
                    "opus" -> OPUS
                    "flac" -> FLAC
                    else -> DEFAULT
                }
            }
        }
    }

    private val preferences: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(context)

    var compressedMediaName: CompressedMediaNameOpts
        get() {
            // 2nd param is the default value
            val preferencesString = preferences.getString(COMPRESSED_MEDIA_NAME, CompressedMediaNameOpts.UUID.name)!!
            return CompressedMediaNameOpts.valueOf(preferencesString) // convert string to enum
        }
        set(value) = setPreference(COMPRESSED_MEDIA_NAME, value.toString())

    var compressedMediaCustomName: String
        get() = preferences.getString(COMPRESSED_MEDIA_CUSTOM_NAME, "")!!
        set(value) = setPreference(COMPRESSED_MEDIA_CUSTOM_NAME, value)

    var videoConversionOutput: Utils.MediaType // UNKNOWN for original
        get() {
            val preferencesString = preferences.getString(CONVERT_VIDEO_OUTPUT, Utils.MediaType.UNKNOWN.name)!!
            return Utils.MediaType.valueOf(preferencesString) // convert string to enum
        }
        set(value) = setPreference(COMPRESSED_MEDIA_NAME, value.toString())
    var imageConversionOutput: Utils.MediaType // UNKNOWN for original
        get() {
            val preferencesString = preferences.getString(CONVERT_IMAGE_OUTPUT, Utils.MediaType.UNKNOWN.name)!!
            return Utils.MediaType.valueOf(preferencesString) // convert string to enum
        }
        set(value) = setPreference(CONVERT_IMAGE_OUTPUT, value.toString())
    var audioConversionOutput: Utils.MediaType // UNKNOWN for original
        get() {
            val preferencesString = preferences.getString(CONVERT_AUDIO_OUTPUT, Utils.MediaType.UNKNOWN.name)!!
            return Utils.MediaType.valueOf(preferencesString) // convert string to enum
        }
        set(value) = setPreference(CONVERT_AUDIO_OUTPUT, value.toString())

    var treatGifsAsVideos: Boolean
        get() = preferences.getBoolean(TREAT_GIFS_AS_VIDEOS, false)
        set(value) = setPreference(TREAT_GIFS_AS_VIDEOS, value)

    var showStatusMessages: Boolean
        get() = preferences.getBoolean(SHOW_STATUS_MESSAGES, true)
        set(value) = setPreference(SHOW_STATUS_MESSAGES, value)

    var videoCrf: Int
        get() = preferences.getString(VIDEO_CRF, "23")!!.toInt()
        set(value) = setPreference(VIDEO_CRF, value.toString())

    var jpegQscale: Int
        get() = preferences.getString(JPEG_QSCALE, "10")!!.toInt()
        set(value) = setPreference(JPEG_QSCALE, value.toString())

    var videoMaxFileSize: Int
        get() = preferences.getString(VIDEO_MAX_FILE_SIZE, "0")!!.toInt()
        set(value) = setPreference(VIDEO_MAX_FILE_SIZE, value.toString())

    var maxVideoResolution: Int
        get() = preferences.getString(MAX_VIDEO_RESOLUTION, "1080")!!.toInt()
        set(value) = setPreference(MAX_VIDEO_RESOLUTION, value.toString())

    var maxImageResolution: Int
        get() = preferences.getString(MAX_IMAGE_RESOLUTION, "2160")!!.toInt()
        set(value) = setPreference(MAX_IMAGE_RESOLUTION, value.toString())

    var copyExifTags: Boolean
        get() = preferences.getBoolean(COPY_EXIF_TAGS, false)
        set(value) = setPreference(COPY_EXIF_TAGS, value)

    var lastVersion: Int
        get() = preferences.getInt(LAST_VERSION, 0)
        set(value) = setPreference(LAST_VERSION, value)

    var saveLogs: Boolean
        get() = preferences.getBoolean(SAVE_LOGS, true)
        set(value) = setPreference(SAVE_LOGS, value)

    var compressionPreset: String
        get() = preferences.getString(COMPRESSION_PRESET, "medium")!!
        set(value) = setPreference(COMPRESSION_PRESET, value)

    var videoCodec: VideoCodecOpts
        get() {
            val videoCodecString = preferences.getString(VIDEO_CODEC, VideoCodecOpts.H264.name)!!
            return VideoCodecOpts.valueOf(videoCodecString) // convert string to enum
        }
        set(value) = setPreference(VIDEO_CODEC, value.toString())

    // bitrate for the hardware AV1 encoder (MediaCodec has no CRF; VBR with a target)
    var av1HardwareBitrateKbps: Int
        get() = preferences.getString(AV1_HW_BITRATE, "6000")!!.toInt()
        set(value) = setPreference(AV1_HW_BITRATE, value.toString())

    var audioCodec: AudioCodecOpts
        get() {
            val audioCodecString = preferences.getString(AUDIO_CODEC, AudioCodecOpts.COPY.name)!!
            return AudioCodecOpts.valueOf(audioCodecString) // convert string to enum
        }
        set(value) = setPreference(AUDIO_CODEC, value.toString())

    // --- deinterlace (bwdif) ---
    enum class DeinterlaceAspectOpts(val dar: String) {
        SOURCE(""),      // keep whatever the source declares
        DAR_4_3("4/3"),
        DAR_16_9("16/9")
    }

    var deinterlace: Boolean
        get() = preferences.getBoolean(DEINTERLACE, false)
        set(value) = setPreference(DEINTERLACE, value)

    // "interlaced" = only frames flagged as interlaced by the decoder, "all" = every frame
    var deinterlaceFrames: String
        get() = preferences.getString(DEINTERLACE_FRAMES, "interlaced")!!
        set(value) = setPreference(DEINTERLACE_FRAMES, value)

    // "send_field" = one frame per field (50p/60p), "send_frame" = one frame per frame (25p/30p)
    var deinterlaceMode: String
        get() = preferences.getString(DEINTERLACE_MODE, "send_field")!!
        set(value) = setPreference(DEINTERLACE_MODE, value)

    // "auto" = use the field order flags of the stream, "tff" / "bff" = force
    var deinterlaceParity: String
        get() = preferences.getString(DEINTERLACE_PARITY, "auto")!!
        set(value) = setPreference(DEINTERLACE_PARITY, value)

    var deinterlaceAspect: DeinterlaceAspectOpts
        get() {
            val aspectString = preferences.getString(DEINTERLACE_ASPECT, DeinterlaceAspectOpts.SOURCE.name)!!
            return DeinterlaceAspectOpts.valueOf(aspectString) // convert string to enum
        }
        set(value) = setPreference(DEINTERLACE_ASPECT, value.toString())

    var deinterlaceUpscale1080: Boolean
        get() = preferences.getBoolean(DEINTERLACE_UPSCALE_1080, false)
        set(value) = setPreference(DEINTERLACE_UPSCALE_1080, value)

    // "bwdif" = motion adaptive deinterlacer (camcorder/TV/PVT video),
    // "fieldmatch" = field matching for film/anime sources (progressive frames are rebuilt from their
    // fields, bwdif only touches frames that stay combed), output always one frame per frame (25p)
    var deinterlaceMethod: String
        get() = preferences.getString(DEINTERLACE_METHOD, "bwdif")!!
        set(value) = setPreference(DEINTERLACE_METHOD, value)

    // --- low bitrate tools ---

    // two ffmpeg runs: analysis pass, then the real encode with the statistics of the whole video
    // (libaom AV1, VP9, x264). Ignored for the hardware AV1 encoder and with custom parameters.
    var twoPass: Boolean
        get() = preferences.getBoolean(TWO_PASS, false)
        set(value) = setPreference(TWO_PASS, value)

    // target video bitrate for two-pass in kbit/s; 0 = keep the CRF (constant quality, two-pass analysis)
    var twoPassBitrateKbps: Int
        get() = preferences.getString(TWO_PASS_BITRATE, "0")!!.toIntOrNull()?.coerceAtLeast(0) ?: 0
        set(value) = setPreference(TWO_PASS_BITRATE, value.toString())

    // libaom cpu-used: 8 = realtime mode (fast, phone default), 0..6 = "good" mode with 48 frames look-ahead
    var av1SoftwareSpeed: Int
        get() = preferences.getString(AV1_SW_SPEED, "8")!!.toIntOrNull()?.coerceIn(0, 8) ?: 8
        set(value) = setPreference(AV1_SW_SPEED, value.toString())

    // libaom in 10 bit (yuv420p10le): less banding in gradients, usually a little smaller, slower
    var av1TenBit: Boolean
        get() = preferences.getBoolean(AV1_10BIT, false)
        set(value) = setPreference(AV1_10BIT, value)

    // "off", "hqdn3d", "nlmeans", "nlmeans_strong"
    var denoise: String
        get() = preferences.getString(DENOISE, "off")!!
        set(value) = setPreference(DENOISE, value)

    // detect black borders once (keyframes of the whole video) and crop them away
    var autoCrop: Boolean
        get() = preferences.getBoolean(AUTO_CROP, false)
        set(value) = setPreference(AUTO_CROP, value)

    // only encode an excerpt of this many seconds (from about 40 % into the video), 0 = whole video
    var testExcerptSeconds: Int
        get() = preferences.getString(TEST_EXCERPT, "0")!!.toIntOrNull()?.coerceAtLeast(0) ?: 0
        set(value) = setPreference(TEST_EXCERPT, value.toString())

    // audio bitrate in kbit/s, 0 = encoder default
    var audioBitrateKbps: Int
        get() = preferences.getString(AUDIO_BITRATE, "0")!!.toIntOrNull()?.coerceAtLeast(0) ?: 0
        set(value) = setPreference(AUDIO_BITRATE, value.toString())

    // 0 = as source, 1 = mono, 2 = stereo
    var audioChannels: Int
        get() = preferences.getString(AUDIO_CHANNELS, "0")!!.toIntOrNull() ?: 0
        set(value) = setPreference(AUDIO_CHANNELS, value.toString())

    // 0 = ffmpeg's choice, 1..4 = n-th audio track, AUDIO_TRACK_ALL = all audio tracks,
    // AUDIO_TRACK_ASK = list the tracks of the shared file and let the user tick them
    var audioTrack: Int
        get() = preferences.getString(AUDIO_TRACK, "0")!!.toIntOrNull() ?: 0
        set(value) = setPreference(AUDIO_TRACK, value.toString())

    // tracks ticked last time (0-based, comma separated), pre-ticked in the next dialog
    var lastAudioTrackSelection: List<Int>?
        get() = preferences.getString(LAST_AUDIO_TRACKS, null)?.let { saved ->
            if (saved.isEmpty()) emptyList() else saved.split(',').mapNotNull { it.trim().toIntOrNull() }
        }
        set(value) = setPreference(LAST_AUDIO_TRACKS, value?.joinToString(",") ?: "")

    // "auto" = ffmpeg's default selection, "copy" = all subtitle tracks (MKV to MKV only),
    // "ask" = list the subtitle tracks of the shared file and let the user tick them (MKV to MKV only), "none"
    var subtitles: String
        get() = preferences.getString(SUBTITLES, "auto")!!
        set(value) = setPreference(SUBTITLES, value)

    // subtitle tracks ticked last time (0-based, comma separated), pre-ticked in the next dialog
    var lastSubtitleTrackSelection: List<Int>?
        get() = preferences.getString(LAST_SUBTITLE_TRACKS, null)?.let { saved ->
            if (saved.isEmpty()) emptyList() else saved.split(',').mapNotNull { it.trim().toIntOrNull() }
        }
        set(value) = setPreference(LAST_SUBTITLE_TRACKS, value?.joinToString(",") ?: "")

    // two-pass: run the video filters once into a lossless FFV1 file (plus the copied audio and subtitle
    // tracks) and let both passes read that instead of filtering the source twice
    var prefilterIntermediate: Boolean
        get() = preferences.getBoolean(PREFILTER_FFV1, false)
        set(value) = setPreference(PREFILTER_FFV1, value)

    // --- long encodes ---

    // foreground service + wake lock: the encode keeps running with the screen off or in the background
    var keepAlive: Boolean
        get() = preferences.getBoolean(KEEP_ALIVE, true)
        set(value) = setPreference(KEEP_ALIVE, value)

    // copy finished videos to Movies/FFShare Deint (the cache copy is deleted after a while)
    var saveCopy: Boolean
        get() = preferences.getBoolean(SAVE_COPY, true)
        set(value) = setPreference(SAVE_COPY, value)

    // the notification permission (Android 13+) is only asked for once
    var notificationPermissionAsked: Boolean
        get() = preferences.getBoolean(NOTIFICATION_PERMISSION_ASKED, false)
        set(value) = setPreference(NOTIFICATION_PERMISSION_ASKED, value)

    /**
     * Anime/cartoon DVD (PAL 576i) to a very small AV1 file. The audio tracks are asked for when
     * sharing; the CRF stays as it is, custom video parameters are cleared because they would override
     * everything else.
     *
     * The values from before are kept for "Undo anime preset". If the preset is still in place
     * unchanged (tapped twice), the existing backup stays: a new one would only hold the preset itself.
     */
    fun applyAnimePreset() {
        val editor = preferences.edit()
        val current = currentValues()
        if (current != ANIME_PRESET) {
            editor.putString(ANIME_PRESET_BACKUP, backupJson(current))
        }
        for ((key, value) in ANIME_PRESET) putValue(editor, key, value)
        editor.apply()
    }

    /** One setting "Undo anime preset" changes; a null value means not set (the app default applies) */
    class PresetChange(val key: String, val now: Any?, val after: Any?)

    /** When the settings from before the anime preset were saved (epoch ms), null without a backup */
    val animePresetBackupTime: Long?
        get() = readBackup()?.savedAt

    /** What "Undo anime preset" would change, in the order of the preset; empty = nothing to do */
    fun animePresetUndoChanges(): List<PresetChange> {
        val target = undoTarget()
        val current = currentValues()
        return ANIME_PRESET.keys.filter { current[it] != target[it] }
            .map { PresetChange(it, current[it], target[it]) }
    }

    /**
     * Puts the settings the anime preset writes back to the backup taken when it was applied. Without
     * a backup (preset applied with 2.0.0-deint.6 or older, or already undone) they go back to the app
     * defaults. Settings the preset does not touch stay as they are.
     */
    fun undoAnimePreset() {
        val editor = preferences.edit()
        for ((key, value) in undoTarget()) putValue(editor, key, value)
        editor.remove(ANIME_PRESET_BACKUP)
        editor.apply()
    }

    private class PresetBackup(val savedAt: Long, val values: Map<String, Any?>)

    // the preset's settings as stored now (String for lists and text fields, Boolean for switches)
    private fun currentValues(): Map<String, Any?> {
        val all = preferences.all
        return ANIME_PRESET.keys.associateWith { all[it] }
    }

    // backup value per setting; a setting the backup has no value for gets the app default
    private fun undoTarget(): Map<String, Any?> {
        val backup = readBackup()?.values
        val defaults = xmlDefaults()
        return ANIME_PRESET.keys.associateWith { backup?.get(it) ?: defaults[it] }
    }

    private fun backupJson(values: Map<String, Any?>): String {
        val json = JSONObject()
        for ((key, value) in values) json.put(key, value ?: JSONObject.NULL)
        return JSONObject()
            .put("saved", System.currentTimeMillis())
            .put("values", json)
            .toString()
    }

    private fun readBackup(): PresetBackup? {
        val raw = preferences.getString(ANIME_PRESET_BACKUP, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val values = json.getJSONObject("values")
            PresetBackup(json.optLong("saved", 0L), ANIME_PRESET.keys.associateWith { key ->
                values.opt(key).takeUnless { it == null || it == JSONObject.NULL }
            })
        } catch (e: JSONException) {
            null
        }
    }

    // android:defaultValue of every setting in preferences.xml, i.e. what a fresh install shows. Read by
    // inflating the settings into a scratch file, so the XML stays the only place that defines them.
    private fun xmlDefaults(): Map<String, Any?> {
        val scratch = context.getSharedPreferences(DEFAULTS_SCRATCH, Context.MODE_PRIVATE)
        scratch.edit().clear().apply()
        PreferenceManager.setDefaultValues(context, DEFAULTS_SCRATCH, Context.MODE_PRIVATE, R.xml.preferences, true)
        val defaults: Map<String, Any?> = HashMap(scratch.all)
        scratch.edit().clear().apply()
        return defaults
    }

    private fun putValue(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            else -> editor.putString(key, value.toString())
        }
    }

    var customVideoParams: String
        get() = preferences.getString(CUSTOM_VIDEO_PARAMS, "")!!
        set(value) = setPreference(CUSTOM_VIDEO_PARAMS, value)

    var customImageParams: String
        get() = preferences.getString(CUSTOM_IMAGE_PARAMS, "")!!
        set(value) = setPreference(CUSTOM_IMAGE_PARAMS, value)

    var customAudioParams: String
        get() = preferences.getString(CUSTOM_AUDIO_PARAMS, "")!!
        set(value) = setPreference(CUSTOM_AUDIO_PARAMS, value)

    private fun setPreference(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }
    private fun setPreference(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }
    private fun setPreference(key: String, value: Int) {
        preferences.edit().putInt(key, value).apply()
    }

    // static variables
    companion object {
        const val COMPRESSED_MEDIA_NAME = "pref_compressed_media_name"
        const val COMPRESSED_MEDIA_CUSTOM_NAME = "pref_compressed_media_custom_name"
        const val CONVERT_VIDEO_OUTPUT = "pref_convert_video_output"
        const val CONVERT_IMAGE_OUTPUT = "pref_convert_image_output"
        const val CONVERT_AUDIO_OUTPUT = "pref_convert_audio_output"
        const val TREAT_GIFS_AS_VIDEOS = "pref_treat_gifs_as_videos"
        const val SHOW_STATUS_MESSAGES = "pref_show_status_messages"
        const val VIDEO_CRF = "pref_video_crf"
        const val JPEG_QSCALE = "pref_jpeg_qscale"
        const val VIDEO_MAX_FILE_SIZE = "pref_video_max_file_size"
        const val MAX_VIDEO_RESOLUTION = "pref_max_video_resolution"
        const val MAX_IMAGE_RESOLUTION = "pref_max_image_resolution"
        const val COPY_EXIF_TAGS = "pref_copy_exif_tags"
        const val LAST_VERSION = "pref_last_version"
        const val SAVE_LOGS = "pref_save_logs"
        const val COMPRESSION_PRESET = "pref_compression_preset"
        const val VIDEO_CODEC = "pref_video_codec"
        const val AUDIO_CODEC = "pref_audio_codec"
        const val AV1_HW_BITRATE = "pref_av1_hw_bitrate"
        const val DEINTERLACE = "pref_deinterlace"
        const val DEINTERLACE_FRAMES = "pref_deinterlace_frames"
        const val DEINTERLACE_MODE = "pref_deinterlace_mode"
        const val DEINTERLACE_PARITY = "pref_deinterlace_parity"
        const val DEINTERLACE_ASPECT = "pref_deinterlace_aspect"
        const val DEINTERLACE_UPSCALE_1080 = "pref_deinterlace_upscale_1080"
        const val DEINTERLACE_METHOD = "pref_deinterlace_method"
        const val TWO_PASS = "pref_two_pass"
        const val TWO_PASS_BITRATE = "pref_two_pass_bitrate"
        const val AV1_SW_SPEED = "pref_av1_sw_speed"
        const val AV1_10BIT = "pref_av1_10bit"
        const val DENOISE = "pref_denoise"
        const val AUTO_CROP = "pref_auto_crop"
        const val TEST_EXCERPT = "pref_test_excerpt"
        const val AUDIO_BITRATE = "pref_audio_bitrate"
        const val AUDIO_CHANNELS = "pref_audio_channels"
        const val AUDIO_TRACK = "pref_audio_track"
        const val SUBTITLES = "pref_subtitles"
        const val KEEP_ALIVE = "pref_keep_alive"
        const val SAVE_COPY = "pref_save_copy"
        const val NOTIFICATION_PERMISSION_ASKED = "pref_notification_permission_asked"
        const val LAST_AUDIO_TRACKS = "pref_last_audio_tracks"
        const val LAST_SUBTITLE_TRACKS = "pref_last_subtitle_tracks"
        const val PREFILTER_FFV1 = "pref_prefilter_ffv1"
        const val AUDIO_TRACK_ALL = -1
        const val AUDIO_TRACK_ASK = -2
        const val CUSTOM_VIDEO_PARAMS = "pref_custom_video_params"
        const val CUSTOM_IMAGE_PARAMS = "pref_custom_image_params"
        const val CUSTOM_AUDIO_PARAMS = "pref_custom_audio_params"

        // settings from before the anime preset as JSON {"saved": ms, "values": {key: value or null}}
        private const val ANIME_PRESET_BACKUP = "pref_anime_preset_backup"
        // scratch SharedPreferences file for reading the defaults of preferences.xml
        private const val DEFAULTS_SCRATCH = "ffshare_default_values_scratch"

        /** Everything the anime preset writes: String for lists and text fields, Boolean for switches */
        private val ANIME_PRESET: Map<String, Any> = linkedMapOf(
            DEINTERLACE to true,
            DEINTERLACE_METHOD to "fieldmatch",
            DEINTERLACE_PARITY to "auto",
            DEINTERLACE_ASPECT to DeinterlaceAspectOpts.SOURCE.name,
            DEINTERLACE_UPSCALE_1080 to false,
            AUTO_CROP to true,
            DENOISE to "nlmeans",
            VIDEO_CODEC to VideoCodecOpts.AV1_SOFTWARE.name,
            AV1_SW_SPEED to "2",
            AV1_10BIT to true,
            TWO_PASS to true,
            TWO_PASS_BITRATE to "150",
            VIDEO_MAX_FILE_SIZE to "0",
            MAX_VIDEO_RESOLUTION to "480",
            AUDIO_CODEC to AudioCodecOpts.OPUS.name,
            AUDIO_BITRATE to "32",
            AUDIO_CHANNELS to "2",
            AUDIO_TRACK to AUDIO_TRACK_ASK.toString(),
            SUBTITLES to "ask",
            CONVERT_VIDEO_OUTPUT to Utils.MediaType.MKV.name,
            TEST_EXCERPT to "0",
            KEEP_ALIVE to true,
            SAVE_COPY to true,
            CUSTOM_VIDEO_PARAMS to ""
        )
    }
}