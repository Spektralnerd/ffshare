package com.caydey.ffshare.utils

import android.content.Context
import androidx.core.app.ActivityCompat
import com.caydey.ffshare.extensions.mediaCacheDir
import java.io.File
import java.util.*
import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.graphics.BitmapFactory
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.arthenica.ffmpegkit.StreamInformation
import com.caydey.ffshare.BuildConfig
import com.caydey.ffshare.R
import timber.log.Timber
import java.io.InputStream
import kotlin.math.ln
import kotlin.math.pow


const val MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 1000
class Utils(private val context: Context) {

    /**
     * Name of a hardware AV1 encoder (MediaCodec, MIME video/av01) or null if the device has none.
     * Android's own software encoder (c2.android.av1.encoder, Android 14+) is deliberately skipped:
     * for that case ffmpeg's libaom is used directly.
     */
    fun hardwareAv1EncoderName(): String? {
        return try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals("video/av01", ignoreCase = true) } }
                .firstOrNull { info ->
                    if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                    else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
                }?.name
        } catch (e: Exception) {
            Timber.w(e, "MediaCodecList query failed")
            null
        }
    }
    private val settings: Settings by lazy { Settings(context) }

    fun getCacheOutputFile(uri: Uri, mediaType: MediaType): Pair<File, MediaType> {
        val fileExtension = getOutputFileMediaType(mediaType) // some files are converted to different formats e.g. mkv to mp4

        var filename = getRandomFilename(fileExtension); // UUID
        if (settings.compressedMediaName == Settings.CompressedMediaNameOpts.ORIGINAL) {
            // if getFilenameFromUri returns null default to randomFilename
            filename = getFilenameFromUri(uri) ?: getRandomFilename(fileExtension)
            filename = filename.substringBeforeLast(".")+".${fileExtension.name.lowercase()}"
        } else if (settings.compressedMediaName == Settings.CompressedMediaNameOpts.CUSTOM) {
           filename = "${settings.compressedMediaCustomName}.${fileExtension.name.lowercase()}"
        }
        Timber.d("Created output file '%s'", filename)
        val outputFile = File(makeCacheUUIDFolder(), filename)
        return Pair(outputFile, fileExtension)
    }
    private fun getOutputFileMediaType(inputFileMediaType: MediaType): MediaType {
        var outputMediaType: MediaType = MediaType.UNKNOWN
        if (isVideo(inputFileMediaType)) {
            outputMediaType = settings.videoConversionOutput;
        } else if (isImage(inputFileMediaType)) {
            if (settings.treatGifsAsVideos && inputFileMediaType == MediaType.GIF) {
                outputMediaType = settings.videoConversionOutput
            } else {
                outputMediaType = settings.imageConversionOutput
            }
        } else if (isAudio(inputFileMediaType)) {
            outputMediaType = settings.audioConversionOutput
        }

        if (outputMediaType == MediaType.UNKNOWN) {
            outputMediaType = inputFileMediaType;
        }
        return outputMediaType
    }
    private fun makeCacheUUIDFolder(): File {
        val cacheUUIDFolder = File(context.mediaCacheDir, UUID.randomUUID().toString())
        cacheUUIDFolder.mkdirs()
        return cacheUUIDFolder
    }

    private fun getRandomFilename(mediaType: MediaType): String {
        return "${UUID.randomUUID()}.${mediaType.name.lowercase()}"
    }
    fun getFilenameFromUri(uri: Uri): String? {
        var filename: String? = null
        val queryCursor = context.contentResolver.query(uri, null, null, null, null)
        queryCursor?.let { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    filename = cursor.getString(nameIndex)
                }
            }
            cursor.close()
        }
        return filename
    }

    // Android 7-9 also asks for WRITE_EXTERNAL_STORAGE: saved copies go straight to the public Movies
    // folder. Same permission group as READ, so an app that already has READ gets it without a dialog.
    private val storagePermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT < 29)
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    val isReadPermissionGranted: Boolean
        get() = storagePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    fun requestReadPermissions(activity: Activity) {
        ActivityCompat.requestPermissions(activity, storagePermissions, MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE)
    }


    private fun getFileHexSignature(inputStream: InputStream): String {
        val arr = ByteArray(16)
        inputStream.read(arr)
        val builder = StringBuilder()
        for (byte in arr) { // foreach byte in head
            val hex = "%02X".format(byte) // format as hex
            builder.append(hex)
        }
        return builder.toString()
    }

    fun getMediaType(uri: Uri): MediaType {
        val filename: String? = getFilenameFromUri(uri)
        var mediaType: MediaType = MediaType.UNKNOWN

        // get type from file extension
        if (filename != null) {
            val extensionsToMediaType = mapOf(
                // images
                "jpg" to MediaType.JPEG,
                "jpeg" to MediaType.JPEG,
                "png" to MediaType.PNG,
                "gif" to MediaType.GIF,
                "webp" to MediaType.WEBP,
                // videos
                "mp4" to MediaType.MP4,
                "mkv" to MediaType.MKV,
                "webm" to MediaType.WEBM,
                "avi" to MediaType.AVI,
                "mov" to MediaType.MP4,
                // audios
                "mp3" to MediaType.MP3,
                "ogg" to MediaType.OGG,
                "aac" to MediaType.AAC,
                "wav" to MediaType.WAV,
                "opus" to MediaType.OPUS
            )

            val extension = filename.substringAfterLast('.').lowercase()
            mediaType = extensionsToMediaType[extension] ?: MediaType.UNKNOWN
        }
        // unable to get filetype from filename extension, using signature detection
        // https://en.wikipedia.org/wiki/List_of_file_signatures
        if (mediaType == MediaType.UNKNOWN) {
            Timber.d("unable to find filetype from extension, trying file signature")
            val inputStream = context.contentResolver.openInputStream(uri)
            val signature = getFileHexSignature(inputStream!!)
            inputStream.close()

            if (signature.startsWith("FFD8FF")) {
                mediaType = MediaType.JPEG
            } else if (signature.startsWith("89504E470D0A1A0A")) {
                mediaType = MediaType.PNG
            } else if (signature.startsWith("47494638")) {
                mediaType = MediaType.GIF
            } else if (signature.startsWith("52494646") && signature.drop(16).startsWith("57454250")) { // 52 49 46 46 ?? ?? ?? ?? 57 45 42 50
                mediaType = MediaType.WEBP
            } else if (signature.drop(8).startsWith("6674797069736F6D")) { // ** ** ** ** 66 74 79 70 69 73 6F 6D
                mediaType = MediaType.MP4
            } else if (signature.startsWith("1A45DFA3")) { // or webm, but assume mkv, also not that big a deal as only happens when filename is not found
                mediaType = MediaType.MKV
            } else if (signature.startsWith("52494646") && signature.drop(16).startsWith("41564920")) { // 52 49 46 46 ** ** ** ** 41 56 49 20
                mediaType = MediaType.AVI
            } else if (signature.startsWith("494433") || signature.startsWith("FFFB")
                || signature.startsWith("FFF3") || signature.startsWith("FFF2")) {
                mediaType = MediaType.MP3
            } else if (signature.startsWith("4F676753")) {
                mediaType = MediaType.OGG
            } else if (signature.startsWith("52494646") && signature.drop(16).startsWith("57415645")) { // 52 49 46 46 ** ** ** ** 57 41 56 45
                mediaType = MediaType.WAV
            }
            if (mediaType == MediaType.UNKNOWN) {
                Timber.d("Unable to find filetype from signature")
            } else {
                Timber.d("Found Filetype from signature $mediaType")
            }
        } else {
            Timber.d("Found Filetype from extension $mediaType")
        }

        return mediaType
    }
    fun getMediaResolution(mediaUri: Uri, mediaType: MediaType): Pair<Int,Int> {
        // image
        if (isImage(mediaType)) {
            val bitmapOption = BitmapFactory.decodeStream(context.contentResolver.openInputStream(mediaUri)!!)
            return Pair(bitmapOption.width, bitmapOption.height)
        }
        // video
        // attempt to get video resolution
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, mediaUri)
        } catch (e: java.lang.RuntimeException) {
            return Pair(0,0) // unable to read video width/height, usually happens with avi files
        }
        // read video resolution (null when Android's extractor does not know the video codec, e.g. MPEG-2 in MKV)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        retriever.release()
        return Pair(width,height)
    }

    /**
     * Copies a finished video out of the cache to Movies/FFShare Deint: via MediaStore on Android 10+
     * (no permission needed), as a plain file on Android 7-9 (WRITE_EXTERNAL_STORAGE). Without that
     * permission Android 7-9 falls back to the app's own folder (Android/data/<package>/files/Movies,
     * deleted together with the app). Returns a readable location or null on failure.
     */
    fun saveCopyToMovies(source: File, displayName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(source.extension.lowercase()) ?: "video/mp4"
                val relativePath = "${Environment.DIRECTORY_MOVIES}/$SAVE_FOLDER"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: return null
                try {
                    resolver.openOutputStream(uri)!!.use { output ->
                        source.inputStream().use { input -> input.copyTo(output, 1 shl 20) }
                    }
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                // MediaStore renames on a name clash ("name (1).mkv"): report the real name
                var savedName = displayName
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) savedName = cursor.getString(0) ?: displayName
                }
                "$relativePath/$savedName"
            } else {
                saveCopyLegacy(source, displayName)
            }
        } catch (e: Exception) {
            Timber.w(e, "Saving a copy of %s failed", source.name)
            null
        }
    }

    // Android 7-9: public Movies folder when the storage permission is there, else the app's own folder
    @Suppress("DEPRECATION") // getExternalStoragePublicDirectory: deprecated from Android 10, used below it only
    private fun saveCopyLegacy(source: File, displayName: String): String? {
        val appFolder = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.let { File(it, SAVE_FOLDER) }
        val canWritePublic = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (canWritePublic) {
            val publicFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), SAVE_FOLDER)
            val target = copyIntoFolder(source, publicFolder, displayName)
            if (target != null) {
                scanMedia(target)
                // copies that earlier versions left in the app's own folder would vanish with an uninstall
                appFolder?.let { moveOldCopies(it, publicFolder) }
                return "${Environment.DIRECTORY_MOVIES}/$SAVE_FOLDER/${target.name}"
            }
            Timber.w("Public Movies folder not writable, saving into the app's own folder")
        } else {
            Timber.w("No storage permission, saving into the app's own folder")
        }
        return appFolder?.let { copyIntoFolder(source, it, displayName) }?.absolutePath
    }

    /** Copies [source] into [folder] as [name] ("name (1).ext" etc. if taken); null on failure, no partial file left */
    private fun copyIntoFolder(source: File, folder: File, name: String): File? {
        if (!folder.isDirectory && !folder.mkdirs()) {
            Timber.w("Cannot create %s", folder)
            return null
        }
        val target = freeFileName(folder, name)
        return try {
            source.copyTo(target)
            target
        } catch (e: FileAlreadyExistsException) {
            null // the name was taken in the meantime: nothing of ours to clean up
        } catch (e: Exception) {
            Timber.w(e, "Copying to %s failed", target)
            target.delete()
            null
        }
    }

    private fun freeFileName(folder: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val extension = if ('.' in name) "." + name.substringAfterLast('.') else ""
        var target = File(folder, name)
        var counter = 1
        while (target.exists()) {
            target = File(folder, "$base ($counter)$extension")
            counter++
        }
        return target
    }

    // Moves what an earlier version saved into the app's own folder (Android 7-9) to the public one.
    // Best effort: must never turn the successful save before it into a failure.
    private fun moveOldCopies(oldFolder: File, newFolder: File) {
        val files = try {
            oldFolder.listFiles()?.filter { it.isFile }
        } catch (e: Exception) {
            null
        } ?: return
        for (file in files) {
            try {
                val target = freeFileName(newFolder, file.name)
                val moved = if (file.renameTo(target)) target
                    else copyIntoFolder(file, newFolder, file.name)?.also { copy ->
                        if (copy.length() == file.length()) file.delete()
                    }
                moved?.let {
                    scanMedia(it)
                    Timber.d("Moved old copy %s to %s", file.name, it)
                }
            } catch (e: Exception) {
                Timber.w(e, "Moving %s failed", file)
            }
        }
        try {
            oldFolder.delete() // only removed when empty
        } catch (e: Exception) {
            Timber.w(e, "Removing %s failed", oldFolder)
        }
    }

    // Android 7-9: without a scan a new file stays invisible to gallery apps and over USB (MTP)
    private fun scanMedia(file: File) {
        try {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), mime?.let { arrayOf(it) }, null)
        } catch (e: Exception) {
            Timber.w(e, "Media scan of %s failed", file)
        }
    }

    fun bytesToHuman(bytes: Long): String {
        // warning will break for files over 1 petabyte in size
        val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
        val byte = 1024f
        if (bytes >= byte) {
            val e = (ln(bytes.toDouble()) / ln(byte)).toInt()
            val humanUnits = bytes / byte.pow(e)
            return String.format("%.01f %s", humanUnits, units[e])
        }
        return "$bytes ${units[0]}"
    }
    fun millisToMicrowaveTime(millis: Int): String {
        var remainder = millis / 1_000
        var time = ""
        for (i in 0..2) {
            val unit = remainder % 60
            remainder /= 60
            if (!(i == 2 && unit == 0)) { // exclude hours if there are none
                time = String.format("%02d:%s", unit, time)
            }
        }
        return time.dropLast(1)
    }

    enum class MediaType {
        MP4, MKV, WEBM, AVI, // videos
        JPEG, JPG, PNG, GIF, WEBP, // images
        MP3, OGG, AAC, WAV, OPUS, // audios
        UNKNOWN
    }
    fun isSupportedMediaType(type: MediaType): Boolean {
        // obviously unsupported if MediaType isn't even known
        return type != MediaType.UNKNOWN
    }
    fun isImage(type: MediaType): Boolean {
        return type == MediaType.JPEG || type == MediaType.PNG || type == MediaType.GIF
                || type == MediaType.WEBP
    }
    fun isVideo(type: MediaType): Boolean {
        return type == MediaType.MP4 || type == MediaType.MKV || type == MediaType.WEBM
                || type == MediaType.AVI
    }
    fun isAudio(type: MediaType): Boolean {
        return type == MediaType.MP3 || type == MediaType.OGG || type == MediaType.AAC
                || type == MediaType.WAV || type == MediaType.OPUS
    }

    /** "Track 2 · Japanese (jpn) · AC3 · stereo · 192 kbit/s · <title>" from the ffprobe stream info */
    fun describeAudioTrack(index: Int, stream: StreamInformation): String {
        val tags = stream.getTags()
        val title = tags?.optString("title", "")?.takeIf { it.isNotBlank() }
        val codec = stream.getCodec()?.uppercase(Locale.ROOT)
        val layout = stream.getChannelLayout()?.takeIf { it.isNotBlank() }
        // MKV from MakeMKV/mkvmerge keeps the bitrate in the BPS tag
        val bitsPerSecond = stream.getBitrate()?.toLongOrNull() ?: statisticsTag(tags, "BPS")?.toLongOrNull()
        val bitrate = bitsPerSecond?.let { "${it / 1000} kbit/s" }
        return listOfNotNull(context.getString(R.string.audio_track_number, index + 1), trackLanguage(tags), codec, layout, bitrate, title)
            .joinToString(" · ")
    }

    /**
     * "Track 5 · German (ger) · VobSub · 235 entries · <title>". The number of entries (from the statistics
     * tags MakeMKV/mkvmerge write) tells the full subtitles from the "forced" tracks with a handful of signs.
     */
    fun describeSubtitleTrack(index: Int, stream: StreamInformation): String {
        val tags = stream.getTags()
        val title = tags?.optString("title", "")?.takeIf { it.isNotBlank() }
        val codec = when (val name = stream.getCodec()?.lowercase(Locale.ROOT)) {
            null -> null
            "dvd_subtitle" -> "VobSub"
            "hdmv_pgs_subtitle" -> "PGS"
            "subrip" -> "SRT"
            else -> name.uppercase(Locale.ROOT)
        }
        val entries = statisticsTag(tags, "NUMBER_OF_FRAMES")?.toIntOrNull()?.let { count ->
            context.resources.getQuantityString(R.plurals.subtitle_entries, count, count)
        }
        return listOfNotNull(context.getString(R.string.audio_track_number, index + 1), trackLanguage(tags), codec, entries, title)
            .joinToString(" · ")
    }

    // "German (ger)" from the language tag; Matroska uses the bibliographic ISO 639-2 codes ("ger"),
    // Android knows the terminology ones
    private fun trackLanguage(tags: org.json.JSONObject?): String? {
        val code = tags?.optString("language", "")?.takeIf { it.isNotBlank() && it != "und" } ?: return null
        val name = Locale(BIBLIOGRAPHIC_LANGUAGES[code] ?: code).getDisplayLanguage(Locale.getDefault())
        return if (name.isNotBlank() && !name.equals(code, ignoreCase = true)) "$name ($code)" else code
    }

    // statistics tag of mkvmerge ("BPS") or MakeMKV ("BPS-eng")
    private fun statisticsTag(tags: org.json.JSONObject?, name: String): String? {
        if (tags == null) return null
        tags.optString(name, "").takeIf { it.isNotBlank() }?.let { return it }
        val keys = tags.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.startsWith("$name-")) return tags.optString(key, "").takeIf { it.isNotBlank() }
        }
        return null
    }

    fun getAllowedMimes(): Array<String> {
        return arrayOf("audio/*", "image/*", "video/*")
    }

    companion object {
        /** Folder below Movies for saved copies */
        const val SAVE_FOLDER = "FFShare Deint"

        private val BIBLIOGRAPHIC_LANGUAGES = mapOf(
            "ger" to "deu", "fre" to "fra", "chi" to "zho", "dut" to "nld", "cze" to "ces",
            "gre" to "ell", "rum" to "ron", "slo" to "slk", "per" to "fas", "wel" to "cym"
        )

        /** 13 h 05 min / 4 min 20 s / 35 s */
        fun formatDuration(seconds: Long): String {
            val s = seconds.coerceAtLeast(0)
            val hours = s / 3600
            val minutes = (s % 3600) / 60
            return when {
                hours > 0 -> String.format(Locale.US, "%d h %02d min", hours, minutes)
                minutes > 0 -> String.format(Locale.US, "%d min %02d s", minutes, s % 60)
                else -> "$s s"
            }
        }
    }
}