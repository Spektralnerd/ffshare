package com.caydey.ffshare.utils


import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.MediaInformation
import com.arthenica.ffmpegkit.Statistics
import com.caydey.ffshare.EncodeService
import com.caydey.ffshare.R
import com.caydey.ffshare.utils.logs.Log
import com.caydey.ffshare.utils.logs.LogsDbHelper
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean


class MediaCompressor(private val context: Context) {
    private val utils: Utils by lazy { Utils(context) }
    private val settings: Settings by lazy { Settings(context) }
    private val logsDbHelper by lazy { LogsDbHelper(context) }

    private val ffmpegParamMaker = FFmpegParamMaker(settings, utils)

    // Guards "is it cancelled?" + "start the next step" against a cancel from another thread, so a
    // cancel can never slip in between and leave an ffmpeg session or the foreground service behind
    private val lock = Any()
    @Volatile private var cancelled = false
    @Volatile private var currentJob: Job? = null
    @Volatile private var currentBatch: Batch? = null
    @Volatile private var keepAliveActive = false
    @Volatile private var ownsBusyFlag = false
    // "2 of 5" when several files were shared, part of the status line
    @Volatile private var fileLabel = ""

    /** True while this compressor's foreground service (wake lock) is running */
    val keepAliveRunning: Boolean get() = keepAliveActive

    /** Stops this compressor's own ffmpeg sessions (never those of another conversion) */
    fun cancelAllOperations() {
        Timber.d("Canceling all ffmpeg operations")
        synchronized(lock) {
            cancelled = true
            currentJob?.abort()
            currentJob = null
            endBatch()
        }
    }

    /** Cancel button or the notification's cancel: stop the conversion and end the batch (main thread) */
    fun cancelByUser() {
        val batch = currentBatch ?: return
        if (batch.isEnded) return
        Toast.makeText(context, context.getString(R.string.ffmpeg_canceled), Toast.LENGTH_LONG).show()
        cancelAllOperations()
        batch.end(stopped = true)
    }

    /** Asks first when the conversion has been running for a while */
    fun confirmCancel(activity: Activity, onConfirmed: () -> Unit) {
        val batch = currentBatch
        val runningFor = if (batch == null) 0L else System.currentTimeMillis() - batch.startedAt
        if (batch == null || batch.isEnded || runningFor < CONFIRM_CANCEL_AFTER_MS) {
            onConfirmed()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.cancel_confirm_title)
            .setMessage(context.getString(R.string.cancel_confirm_message, Utils.formatDuration(runningFor / 1000)))
            .setPositiveButton(R.string.cancel_confirm_yes) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.cancel_confirm_no, null)
            .show()
    }

    /** Labels of the audio and subtitle tracks of a file, for the selection dialogs when sharing */
    class TrackLabels(val audio: List<String>, val subtitles: List<String>)

    /**
     * Audio and subtitle tracks of the first video among the shared files ("Track 2 · Japanese (jpn) ·
     * AC3 · stereo · 192 kbit/s"). One ffprobe run for both lists: not on the main thread.
     */
    fun describeTracks(uris: List<Uri>): TrackLabels {
        val none = TrackLabels(emptyList(), emptyList())
        val uri = uris.firstOrNull { utils.isVideo(utils.getMediaType(it)) } ?: return none
        val information = FFprobeKit.getMediaInformation(FFmpegKitConfig.getSafParameterForRead(context, uri)).getMediaInformation()
            ?: return none
        val streams = information.getStreams()
        // subtitle tracks are only picked MKV -> MKV (every subtitle format can be copied there)
        val inputType = utils.getMediaType(uri)
        val outputType = settings.videoConversionOutput.takeIf { it != Utils.MediaType.UNKNOWN } ?: inputType
        val subtitlesPossible = inputType == Utils.MediaType.MKV && outputType == Utils.MediaType.MKV
        return TrackLabels(
            streams.filter { it.getType()?.lowercase() == "audio" }
                .mapIndexed { index, stream -> utils.describeAudioTrack(index, stream) },
            if (!subtitlesPossible) emptyList()
            else streams.filter { it.getType()?.lowercase() == "subtitle" }
                .mapIndexed { index, stream -> utils.describeSubtitleTrack(index, stream) }
        )
    }

    /**
     * audioTrackSelection / subtitleTrackSelection: tracks ticked in the dialogs (0-based), null = the
     * "Audio track" / "Subtitles" setting decides
     */
    fun compressFiles(activity: Activity, inputFilesUri: ArrayList<Uri>, audioTrackSelection: List<Int>?,
                      subtitleTrackSelection: List<Int>?, callback: (uris: ArrayList<Uri>) -> Unit) {
        ffmpegParamMaker.audioTrackSelection = audioTrackSelection
        ffmpegParamMaker.subtitleTrackSelection = subtitleTrackSelection
        synchronized(lock) {
            // one conversion at a time; also keeps the cache clean-up away while files are written
            busy = true
            ownsBusyFlag = true
            cancelled = false
        }
        // nothing else can be converting now: leftovers of killed runs (input copies, pass logs) go
        removeWorkFiles()

        val batch = Batch(activity, inputFilesUri, callback)
        currentBatch = batch
        EncodeService.cancelHook = { cancelByUser() }
        activity.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            confirmCancel(activity) { cancelByUser() }
        }
        // long commands (two-pass) scroll inside their box instead of pushing the screen apart
        activity.findViewById<TextView>(R.id.txtFfmpegCommand).movementMethod = ScrollingMovementMethod()
        batch.next(0) // start iterations
    }

    @SuppressLint("SetTextI18n")
    private fun compressSingleFile(
        activity: Activity,
        inputFileUri: Uri,
        successHandler: (uri: Uri, file: File, inputFileSize: Long, outputFileSize: Long) -> Unit,
        failureHandler: () -> Unit
    ) {
        val views = Views(activity)

        val mediaType = utils.getMediaType(inputFileUri)
        if (!utils.isSupportedMediaType(mediaType)) { // not supported show error
            ui { Toast.makeText(context, context.getString(R.string.error_unknown_filetype), Toast.LENGTH_LONG).show() }
            failureHandler()
            return
        }

        // don't show progress when compressing images (not possible)
        val showProgress = !utils.isImage(mediaType)
        ui { views.processedTableRow.visibility = if (showProgress) View.VISIBLE else View.INVISIBLE }

        val inputFileName = utils.getFilenameFromUri(inputFileUri) ?: "unknown"

        // get output file, (random uuid, custom name, original name)
        val (outputFile, outputMediaType) = utils.getCacheOutputFile(inputFileUri, mediaType)

        // get Uri from File, needs to be this way not Uri.fromFile(...) to go through security
        val outputFileUri = FileProvider.getUriForFile(context, context.applicationContext.packageName+".fileprovider", outputFile)

        // need to create new saf param as they are one-use
        val mediaInformation = FFprobeKit.getMediaInformation(FFmpegKitConfig.getSafParameterForRead(context, inputFileUri)).getMediaInformation()

        if (mediaInformation == null) {
            Timber.d("Unable to get media information, throwing error")
            ui { Toast.makeText(context, context.getString(R.string.error_invalid_file), Toast.LENGTH_LONG).show() }
            failureHandler()
            return
        }

        val inputFileSize = mediaInformation.getSize()?.toLong() ?: 0 // get input file size

        var duration = 0 // default duration for image
        if (showProgress) {
            // invalid video file if ffprobe cant parse duration and size
            if (mediaInformation.getDuration() == null || mediaInformation.getSize() == null) {
                Timber.d("Unable to get size & duration for media, throwing error")
                ui { Toast.makeText(context, context.getString(R.string.error_invalid_file), Toast.LENGTH_LONG).show() }
                failureHandler()
                return
            }
            // a test excerpt is shorter than the file
            duration = (ffmpegParamMaker.effectiveDurationSeconds(mediaInformation, mediaType) * 1_000).toInt()
        }

        val job = Job(views, inputFileUri, inputFileName, inputFileSize, mediaType, mediaInformation,
            outputFile, outputFileUri, outputMediaType, duration, showProgress, successHandler, failureHandler)

        synchronized(lock) {
            // cancelled while this file was being prepared: the cancel path ends the batch
            if (cancelled) return
            currentJob = job
            // keep-alive: foreground service + wake lock for everything that is not a quick image
            if (settings.keepAlive && showProgress) {
                if (!keepAliveActive) {
                    keepAliveActive = EncodeService.start(context, inputFileName)
                } else {
                    EncodeService.update(context, inputFileName, -1)
                }
            }
        }

        // set TextViews
        ui {
            views.txtFfmpegCommand.text = ""
            views.txtInputFile.text = inputFileName
            views.txtInputFileSize.text = utils.bytesToHuman(inputFileSize)
            views.txtOutputFile.text = outputFile.name
            views.txtOutputFileSize.text = utils.bytesToHuman(0)
            views.txtProcessedTime.text = utils.millisToMicrowaveTime(0)
            views.txtProcessedTimeTotal.text = utils.millisToMicrowaveTime(duration)
            views.txtProcessedPercent.text = context.getString(R.string.format_percentage, 0.0f)
            views.txtCommandNumber.text = fileLabel
        }

        job.start()
    }

    // must be called with the lock held
    private fun endBatch() {
        // never touch the service of another conversion (a compressor that was turned away as busy)
        if (keepAliveActive) {
            EncodeService.stop(context)
            keepAliveActive = false
        }
        if (ownsBusyFlag) {
            EncodeService.cancelHook = null
            busy = false
            ownsBusyFlag = false
        }
    }

    // private copies of shared inputs, pass logs and outputs in progress; not in the cache, which Android
    // may clear when storage runs low, and not in the auto backup
    private fun workDir(name: String): File = File(context.noBackupFilesDir, name).apply { mkdirs() }

    private fun removeWorkFiles() {
        removeLeftovers(context)
    }

    private fun ui(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }

    private class Views(activity: Activity) {
        val txtFfmpegCommand: TextView = activity.findViewById(R.id.txtFfmpegCommand)
        val txtInputFile: TextView = activity.findViewById(R.id.txtInputFile)
        val txtInputFileSize: TextView = activity.findViewById(R.id.txtInputFileSize)
        val txtOutputFile: TextView = activity.findViewById(R.id.txtOutputFile)
        val txtOutputFileSize: TextView = activity.findViewById(R.id.txtOutputFileSize)
        val txtProcessedTime: TextView = activity.findViewById(R.id.txtProcessedTime)
        val txtProcessedTimeTotal: TextView = activity.findViewById(R.id.txtProcessedTimeTotal)
        val txtProcessedPercent: TextView = activity.findViewById(R.id.txtProcessedPercent)
        val txtCommandNumber: TextView = activity.findViewById(R.id.txtCommandNumber)
        val processedTableRow: TableRow = activity.findViewById(R.id.processedTableRow)
    }

    /** The shared files, one after the other. A failed file does not stop the others, a cancel does. */
    private inner class Batch(val activity: Activity, val uris: List<Uri>, val callback: (uris: ArrayList<Uri>) -> Unit) {
        val startedAt = System.currentTimeMillis()
        private val compressedFiles = ArrayList<Uri>()
        private val compressedOutputs = ArrayList<File>()
        private var totalInputFileSize = 0L
        private var totalOutputFileSize = 0L
        @Volatile private var hadError = false
        private val ended = AtomicBoolean(false)

        val isEnded: Boolean get() = ended.get()

        fun next(i: Int) {
            if (ended.get()) return
            if (i >= uris.size || cancelled) {
                end(stopped = cancelled)
                return
            }
            Timber.d("Processing %d of %d files", i+1, uris.size)
            fileLabel = if (uris.size > 1) context.getString(R.string.command_x_of_y, i+1, uris.size) else ""
            try {
                compressSingleFile(activity, uris[i], failureHandler = {
                    // if 1 file fails don't add it to compressedFiles array
                    hadError = true
                    next(i+1)
                }, successHandler = { uri, file, inputFileSize, outputFileSize ->
                    totalInputFileSize += inputFileSize
                    totalOutputFileSize += outputFileSize
                    compressedFiles.add(uri)
                    compressedOutputs.add(file)
                    next(i+1)
                })
            } catch (e: Exception) {
                // e.g. the read permission of a shared file ended (the sharing app was closed)
                Timber.e(e, "Preparing file %d failed", i+1)
                ui { Toast.makeText(context, context.getString(R.string.ffmpeg_error), Toast.LENGTH_LONG).show() }
                hadError = true
                next(i+1)
            }
        }

        fun end(stopped: Boolean) {
            if (!ended.compareAndSet(false, true)) return
            // show compression percentage as toast message if there was no error
            if (settings.showStatusMessages && !hadError && !stopped && totalInputFileSize > 0) {
                val totalOutputFileSizeHuman = utils.bytesToHuman(totalOutputFileSize)
                val compressionPercentage = (1 - (totalOutputFileSize.toDouble() / totalInputFileSize)) * 100
                val toastMessage = context.getString(R.string.media_reduction_message, totalOutputFileSizeHuman, compressionPercentage)
                ui {
                    Timber.d("Showing compression size toast message")
                    Toast.makeText(context, toastMessage, Toast.LENGTH_LONG).show()
                }
            }
            // The cache clean-up (scheduled when the activity finishes) deletes folders older than an
            // hour; the first files of a long batch were finished days ago, but are shared only now
            val now = System.currentTimeMillis()
            for (file in compressedOutputs) {
                file.setLastModified(now)
                file.parentFile?.setLastModified(now)
            }
            synchronized(lock) {
                currentJob = null
                endBatch()
            }
            currentBatch = null
            callback(compressedFiles)
        }
    }

    /**
     * One file: [private copy of the input] -> [border detection] -> one ffmpeg run, or the two passes
     * of a two-pass encode. Each ffmpeg step is an async session; the next one starts in its completion
     * callback. Exactly one of successHandler/failureHandler is called, unless the user cancels.
     */
    private inner class Job(
        val views: Views,
        val inputFileUri: Uri,
        val inputFileName: String,
        val inputFileSize: Long,
        val mediaType: Utils.MediaType,
        val mediaInformation: MediaInformation,
        val outputFile: File,
        val outputFileUri: Uri,
        val outputMediaType: Utils.MediaType,
        val durationMs: Int,
        val showProgress: Boolean,
        val successHandler: (uri: Uri, file: File, inputFileSize: Long, outputFileSize: Long) -> Unit,
        val failureHandler: () -> Unit
    ) {
        private val startedAt = System.currentTimeMillis()
        private val done = AtomicBoolean(false)      // no further steps
        private val reported = AtomicBoolean(false)  // success/failure handler called (or cancelled)
        private val uuid = UUID.randomUUID().toString()
        // settings that must stay the same for every step of this file, even if they change meanwhile
        private val twoPass = ffmpegParamMaker.usesTwoPass(mediaType, outputMediaType)
        private val cropDetect = ffmpegParamMaker.wantsCropDetect(mediaType, outputMediaType)
        private val slowEncode = ffmpegParamMaker.isSlowEncode(mediaType, outputMediaType)
        private val excerptParams = ffmpegParamMaker.excerptInputParams(mediaInformation, mediaType)
        private val passLogPrefix = File(workDir(PASSLOG_DIR), uuid).absolutePath
        private val workOutput = File(workDir(WORK_DIR), "$uuid.${outputFile.extension}")
        private val fullDurationMs = ((mediaInformation.getDuration()?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
        private val prettyCommands: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
        // Per-frame "frame number,time" of the frames entering the video encoder. ffmpeg 8.1 reports as
        // time the dts of the slowest output stream and nothing while any stream has no packet yet:
        // the video in pass 1 (libaom/VP9 put out no packets there) and sparse DVD subtitles in pass 2
        // (a clip or an opening without subtitles) - progress and remaining time come from this file.
        private val progressFile = File(workDir(WORK_DIR), "$uuid.progress")
        private val encoderStats = ffmpegParamMaker.usesEncoderStats(mediaType, outputMediaType)
        @Volatile private var passStartedAt = 0L
        @Volatile private var currentSession: FFmpegSession? = null
        private val notes = Collections.synchronizedList(ArrayList<String>())
        // ids of this job's ffmpeg sessions; cancelling a finished one is a no-op
        private val sessionIds = Collections.synchronizedList(ArrayList<Long>())
        @Volatile private var localInput: File? = null
        @Volatile private var crop: FFmpegParamMaker.Crop? = null
        @Volatile private var secondPassParams: String? = null
        // STEP_COPY, STEP_BORDERS, STEP_PREFILTER, 0 single run, 1/2 two-pass
        @Volatile private var currentPass = STEP_COPY
        @Volatile private var stage = ""
        private var lastNotification = 0L
        // "Filter once (FFV1)": the filters run once into this lossless file, both passes read it
        private val prefilter = ffmpegParamMaker.usesPrefilter(mediaType, outputMediaType)
        private val intermediateFile = File(workDir(WORK_DIR), "$uuid.ffv1.mkv")
        @Volatile private var intermediate: File? = null    // set once the intermediate is complete
        @Volatile private var prefilterRan = false          // the progress bar is split in three then
        @Volatile private var stopPrefilter = false         // stopped on purpose (storage), not a user cancel

        fun start() {
            guarded {
                prepareInput {
                    if (cropDetect) detectBorders() else afterBorders()
                }
            }
        }

        private fun afterBorders() {
            when {
                !prefilter -> runPass(if (twoPass) 1 else 0)
                excerptParams.isNotEmpty() -> {
                    // -ss cuts copied tracks only at a key frame: the intermediate of an excerpt would start
                    // with up to a GOP of extra sound before the first picture
                    notes.add("Filter once (FFV1): not used for test excerpts")
                    runPass(1)
                }
                else -> runPrefilter()
            }
        }

        /** User cancel: stop everything, call no handler; the ffmpeg output so far goes to the logs */
        fun abort() {
            done.set(true)
            val wasRunning = !reported.getAndSet(true)
            cancelSessions()
            if (!wasRunning) {
                cleanUp()
                return
            }
            val session = currentSession
            val runningFor = Utils.formatDuration((System.currentTimeMillis() - startedAt) / 1000)
            Thread({
                try {
                    cleanUp()
                    if (settings.saveLogs) {
                        // getLogs() does not wait for pending messages (this may run on the main thread)
                        val output = "[FFShare Deint] canceled after $runningFor\n" +
                                (session?.let { logText(it.getLogs()) } ?: "")
                        logsDbHelper.addLog(Log(commandsText(), inputFileName, outputFile.name, false, output, inputFileSize, -1))
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Saving the log of a canceled conversion failed")
                }
            }, "ffshare-cancel-log").start()
        }

        private fun cancelSessions() {
            synchronized(sessionIds) { sessionIds.toList() }.forEach { FFmpegKit.cancel(it) }
        }

        // input copy, FFV1 intermediate, pass logs, unfinished output
        private fun cleanUp() {
            val prefix = File(passLogPrefix)
            prefix.parentFile?.listFiles()?.filter { it.name.startsWith(prefix.name) }?.forEach { it.delete() }
            localInput?.delete()
            intermediateFile.delete()
            workOutput.delete()
            progressFile.delete()
        }

        // FFmpegKit's executor swallows exceptions thrown in completion callbacks; that would leave the
        // job (and the wake lock) hanging forever, so every step fails the job instead
        private fun guarded(block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                Timber.e(e, "Conversion step failed")
                cancelSessions()
                finishFailure("Internal error: ${e.stackTraceToString()}")
            }
        }

        /**
         * Jobs with several ffmpeg runs open the input several times, possibly days apart. The read
         * permission of a shared file ends when the sharing app's task is closed, so those jobs work on
         * a private copy (if there is room; otherwise every run opens the shared file again).
         */
        private fun prepareInput(next: () -> Unit) {
            val runs = (if (cropDetect) 1 else 0) + (if (twoPass) 2 else 1)
            val dir = workDir(INPUT_DIR)
            // a test excerpt is over in minutes, copying gigabytes for it is not worth it
            if (runs < 2 || excerptParams.isNotEmpty()) {
                next()
                return
            }
            if (inputFileSize <= 0 || dir.usableSpace < inputFileSize + COPY_SPACE_RESERVE) {
                notes.add("No private copy of the input (not enough free space), reading the shared file in every run")
                next()
                return
            }
            currentPass = STEP_COPY
            stage = context.getString(R.string.status_copying_input)
            showStage()
            Thread({
                guarded {
                    val target = File(dir, "$uuid.${mediaType.name.lowercase()}")
                    try {
                        copyInput(target)
                        localInput = target
                        notes.add("Input copied to app storage (${utils.bytesToHuman(inputFileSize)})")
                    } catch (e: IOException) {
                        target.delete()
                        if (!done.get() && !cancelled) {
                            Timber.w(e, "Private copy of the input failed")
                            notes.add("Private copy failed (${e.message}), reading the shared file in every run")
                        }
                    }
                    if (!done.get() && !cancelled) next()
                }
            }, "ffshare-input-copy").start()
        }

        private fun copyInput(target: File) {
            val input = context.contentResolver.openInputStream(inputFileUri) ?: throw IOException("no input stream")
            input.use { source ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    var lastUpdate = 0L
                    while (true) {
                        if (done.get() || cancelled) throw InterruptedIOException("cancelled")
                        val read = source.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 500) {
                            lastUpdate = now
                            val percent = (copied * 100.0 / inputFileSize).toFloat()
                            ui { views.txtProcessedPercent.text = context.getString(R.string.format_percentage, percent) }
                        }
                    }
                }
            }
        }

        private fun detectBorders() {
            currentPass = STEP_BORDERS
            stage = context.getString(R.string.status_crop_detect)
            showStage()
            val command = "${ffmpegParamMaker.cropDetectInputParams} -i ${inputArgument()} ${ffmpegParamMaker.cropDetectParams}"
            execute(command) { session ->
                if (session.getReturnCode()?.isValueSuccess() == true) {
                    crop = ffmpegParamMaker.parseCropDetect(lastLogLines(session, 300), mediaInformation)
                    notes.add("Border detection: ${crop?.filter ?: "nothing to crop"}")
                } else {
                    notes.add("Border detection failed (return code ${session.getReturnCode()?.value}), no cropping")
                }
                afterBorders()
            }
        }

        /**
         * Runs deinterlacing, cropping, denoising and scaling once into a lossless FFV1 file that carries
         * all audio/subtitle tracks and attachments unchanged; both passes read it instead of the source.
         * Anything that goes wrong (no room, a track MKV cannot hold) falls back to the usual two passes.
         */
        private fun runPrefilter() {
            val chain = ffmpegParamMaker.prefilterChain(inputFileUri, mediaInformation, mediaType, outputMediaType, crop)
            if (chain == null) {
                notes.add("Filter once (FFV1): no filters to run, skipped")
                runPass(1)
                return
            }
            val free = workDir(WORK_DIR).usableSpace
            if (free < PREFILTER_MIN_FREE * 2) {
                notes.add("Filter once (FFV1): only ${utils.bytesToHuman(free)} free, skipped")
                runPass(1)
                return
            }
            currentPass = STEP_PREFILTER
            prefilterRan = true
            stage = context.getString(R.string.status_prefilter)
            var statsParams = ""
            if (encoderStats) {
                progressFile.delete()
                statsParams = " -stats_enc_pre:v ${progressFile.absolutePath} -stats_enc_pre_fmt:v {n},{t}"
            }
            val statsPeriod = if (slowEncode) " -stats_period 5" else ""
            val params = ffmpegParamMaker.prefilterParams(chain)
            val command = "-y$statsPeriod -i ${inputArgument()} $params$statsParams ${intermediateFile.absolutePath}"
            val prettyCommand = "ffmpeg -y$statsPeriod -i $inputFileName $params$statsParams ${intermediateFile.name}"
            prettyCommands.add(prettyCommand)
            ui {
                views.txtFfmpegCommand.text = prettyCommand
                views.txtProcessedTime.text = utils.millisToMicrowaveTime(0)
            }
            showStage()
            passStartedAt = System.currentTimeMillis()
            execute(command) { session ->
                if (session.getReturnCode()?.isValueSuccess() == true && !stopPrefilter && intermediateFile.length() > 0) {
                    intermediate = intermediateFile
                    notes.add("Filter once (FFV1): intermediate ${utils.bytesToHuman(intermediateFile.length())}, " +
                            "filtered in ${Utils.formatDuration((System.currentTimeMillis() - passStartedAt) / 1000)}")
                    // from here on only the intermediate is read: the private copy of the source can go
                    localInput?.let { copy ->
                        localInput = null
                        if (copy.delete()) notes.add("Private copy of the input deleted early (the passes read the intermediate)")
                    }
                } else {
                    if (!stopPrefilter) {
                        notes.add("Filter once (FFV1) failed (return code ${session.getReturnCode()?.value}), " +
                                "filtering in both passes instead:\n${lastLogLines(session, 15)}")
                    }
                    intermediateFile.delete()
                }
                runPass(1)
            }
        }

        // Filter once: stop early when the intermediate will not fit (projected from what is written so
        // far) or the phone is running out of storage; the passes then filter the source as usual
        private fun checkPrefilterSpace(writtenBytes: Long, fraction: Double) {
            if (currentPass != STEP_PREFILTER || stopPrefilter) return
            val free = intermediateFile.parentFile?.usableSpace ?: return
            val projected = if (fraction >= 0.02 && writtenBytes > 0) (writtenBytes / fraction).toLong() else 0L
            val stillNeeded = (projected - writtenBytes).coerceAtLeast(0)
            if (free < PREFILTER_MIN_FREE || (projected > 0 && stillNeeded > free - PREFILTER_MIN_FREE)) {
                stopPrefilter = true
                notes.add("Filter once (FFV1) stopped: ${utils.bytesToHuman(free)} free" +
                        (if (projected > 0) ", the intermediate would need about ${utils.bytesToHuman(projected)}" else "") +
                        "; filtering in both passes instead")
                currentSession?.let { FFmpegKit.cancel(it.getSessionId()) }
            }
        }

        private fun runPass(pass: Int) {
            currentPass = pass
            stage = when (pass) {
                1, 2 -> context.getString(R.string.status_pass, pass, 2)
                else -> ""
            }
            if (excerptParams.isNotEmpty()) {
                val excerptLabel = context.getString(R.string.status_test_excerpt, Utils.formatDuration((durationMs / 1000).toLong()))
                stage = if (stage.isEmpty()) excerptLabel else "$stage · $excerptLabel"
            }

            // both passes are built before the first one starts: settings changed during a pass of
            // several hours must not produce a second pass that does not match the statistics
            val prefiltered = intermediate != null
            val params = when (pass) {
                1 -> {
                    secondPassParams = ffmpegParamMaker.create(inputFileUri, mediaInformation, mediaType, outputMediaType, 2, crop, prefiltered)
                    ffmpegParamMaker.create(inputFileUri, mediaInformation, mediaType, outputMediaType, 1, crop, prefiltered)
                }
                2 -> secondPassParams ?: ffmpegParamMaker.create(inputFileUri, mediaInformation, mediaType, outputMediaType, 2, crop, prefiltered)
                else -> ffmpegParamMaker.create(inputFileUri, mediaInformation, mediaType, outputMediaType, 0, crop)
            }
            var passParams = if (pass == 0) "" else " -pass $pass -passlogfile $passLogPrefix"
            if (encoderStats) {
                progressFile.delete()
                passParams += " -stats_enc_pre:v ${progressFile.absolutePath} -stats_enc_pre_fmt:v {n},{t}"
            }
            // long runs: progress every 5 s instead of 0.5 s; FFmpegKit keeps every log line and every
            // statistics entry of a session in memory, over days that would end in an OutOfMemoryError
            val statsPeriod = if (slowEncode) " -stats_period 5" else ""
            // (test excerpts never use the intermediate)
            val input = if (excerptParams.isEmpty() || prefiltered) "-i" else "$excerptParams -i"
            val inputName = if (prefiltered) intermediateFile.name else inputFileName
            val command: String
            val prettyCommand: String
            if (pass == 1) {
                // analysis pass: statistics only, the video itself is thrown away
                command = "-y$statsPeriod $input ${inputArgument()} $params$passParams -f null -"
                prettyCommand = "ffmpeg -y$statsPeriod $input $inputName $params$passParams -f null -"
            } else {
                // written outside the cache first, moved there (where sharing expects it) when finished
                command = "-y$statsPeriod $input ${inputArgument()} $params$passParams ${workOutput.absolutePath}"
                prettyCommand = "ffmpeg -y$statsPeriod $input $inputName $params$passParams ${outputFile.name}"
            }
            prettyCommands.add(prettyCommand)

            ui {
                views.txtFfmpegCommand.text = prettyCommand
                views.txtProcessedTime.text = utils.millisToMicrowaveTime(0)
            }
            showStage()
            passStartedAt = System.currentTimeMillis()
            execute(command) { session ->
                if (session.getReturnCode()?.isValueSuccess() == true) {
                    if (pass == 1) runPass(2) else succeed(session)
                } else {
                    finishFailure(sessionLog(session))
                }
            }
        }

        // the FFV1 intermediate once it exists, else the private copy when there is one, otherwise a fresh
        // (one-use) SAF parameter
        private fun inputArgument(): String {
            return intermediate?.absolutePath ?: localInput?.absolutePath
                ?: FFmpegKitConfig.getSafParameterForRead(context, inputFileUri)
        }

        /** Starts an ffmpeg session unless the job is over; the completion handler runs guarded */
        private fun execute(command: String, onComplete: (FFmpegSession) -> Unit) {
            Timber.d("Executing ffmpeg command: 'ffmpeg %s'", command)
            synchronized(lock) {
                if (done.get() || cancelled) return
                val session = FFmpegKit.executeAsync(command, { session ->
                    try {
                        val returnCode = session.getReturnCode()
                        if (done.get()) {
                            Timber.d("ffmpeg session ended after the job was finished")
                        } else if (cancelled || (returnCode?.isValueCancel() == true && !stopPrefilter)) {
                            // (a filter-once step stopped for lack of storage continues with the passes)
                            // cancel paths (button, back key, notification, onStop) end the job themselves
                            Timber.d("ffmpeg command canceled")
                        } else {
                            guarded { onComplete(session) }
                        }
                    } finally {
                        // drop the session's logs and statistics from FFmpegKit's history
                        FFmpegKitConfig.deleteSession(session.getSessionId())
                    }
                }, { /* logs */ }, { statistics ->
                    try {
                        onStatistics(statistics)
                    } catch (e: Exception) {
                        Timber.w(e, "Progress update failed")
                    }
                })
                sessionIds.add(session.getSessionId())
                currentSession = session
            }
        }

        private fun succeed(session: FFmpegSession) {
            if (done.getAndSet(true)) return
            Timber.d("ffmpeg command executed successfully")
            // total time of the file, to compare settings (e.g. with and without "Filter once")
            val took = Utils.formatDuration((System.currentTimeMillis() - startedAt) / 1000)
            notes.add("Finished in $took")
            val log = sessionLog(session)
            try {
                // the finished file goes where the share intent and the cache clean-up expect it
                outputFile.parentFile?.mkdirs()
                if (!workOutput.renameTo(outputFile)) {
                    workOutput.copyTo(outputFile, overwrite = true)
                    workOutput.delete()
                }
                // the cache clean-up removes folders older than an hour: count from now, not from the
                // start of a pass that ran for days
                val now = System.currentTimeMillis()
                outputFile.setLastModified(now)
                outputFile.parentFile?.setLastModified(now)
                if (settings.copyExifTags && ExifTools.isValidType(mediaType)) {
                    Timber.d("copying exif tags")
                    ExifTools.copyExif(context.contentResolver.openInputStream(inputFileUri)!!, outputFile)
                }
            } catch (e: Exception) {
                Timber.e(e, "Finishing the output failed")
                finishFailure("$log\nFinishing the output failed: ${e.stackTraceToString()}")
                return
            }
            if (!reported.compareAndSet(false, true)) return
            val outputFileCurrentSize = outputFile.length()
            // everything below is best effort; the handler must run in any case, or the batch would
            // hang with the wake lock held
            try {
                cleanUp()
                // """Only the original thread that created a view hierarchy can touch its views."""
                ui {
                    // update TextViews to their final values 97.8% -> 100.0%
                    views.txtProcessedPercent.text = context.getString(R.string.format_percentage, 100.0f)
                    views.txtProcessedTime.text = utils.millisToMicrowaveTime(durationMs)
                    if (outputFileCurrentSize > 0) {
                        views.txtOutputFileSize.text = utils.bytesToHuman(outputFileCurrentSize)
                    }
                }

                // a copy outside the cache: the cache is cleaned an hour after sharing, and after a run
                // of several days nobody may be there to pick a share target
                var savedTo: String? = null
                if (settings.saveCopy && utils.isVideo(outputMediaType) && outputFileCurrentSize > 0) {
                    savedTo = utils.saveCopyToMovies(outputFile, copyName())
                    savedTo?.let { location ->
                        ui { Toast.makeText(context, context.getString(R.string.saved_copy_toast, location), Toast.LENGTH_LONG).show() }
                    }
                }

                if (isLongRun()) {
                    val size = context.getString(R.string.notification_size_time, utils.bytesToHuman(outputFileCurrentSize), took)
                    EncodeService.notifyResult(context, context.getString(R.string.notification_done_title, inputFileName),
                        if (savedTo != null) context.getString(R.string.notification_done_saved, size, savedTo)
                        else context.getString(R.string.notification_done, size))
                }

                logsDbHelper.addLog(Log(
                    commandsText(),
                    inputFileName,
                    outputFile.name,
                    true,
                    log,
                    inputFileSize,
                    outputFileCurrentSize
                ))
            } catch (e: Exception) {
                Timber.e(e, "Reporting the result failed")
            } finally {
                // callback
                successHandler(outputFileUri, outputFile, inputFileSize, outputFileCurrentSize)
            }
        }

        private fun finishFailure(log: String) {
            done.set(true)
            if (!reported.compareAndSet(false, true)) return
            Timber.d("ffmpeg command failed")
            // best effort (a full disk can make even the log database throw); the handler runs anyway
            try {
                cleanUp()
                ui { Toast.makeText(context, context.getString(R.string.ffmpeg_error), Toast.LENGTH_LONG).show() }
                if (isLongRun()) {
                    EncodeService.notifyResult(context, context.getString(R.string.notification_failed_title, inputFileName),
                        context.getString(R.string.notification_failed))
                }
                // save log
                logsDbHelper.addLog(Log(
                    commandsText(),
                    inputFileName,
                    outputFile.name,
                    false,
                    log,
                    inputFileSize,
                    -1
                ))
            } catch (e: Exception) {
                Timber.e(e, "Reporting the failure failed")
            } finally {
                failureHandler()
            }
        }

        /**
         * Log for the database: notes of this job, then the beginning and the end of the ffmpeg output
         * (built from the log list; one string of a multi-day output could exhaust the memory)
         */
        private fun sessionLog(session: FFmpegSession): String {
            return try {
                buildSessionLog(session)
            } catch (e: Exception) {
                Timber.w(e, "Collecting the ffmpeg output failed")
                "ffmpeg output not available: $e"
            }
        }

        private fun buildSessionLog(session: FFmpegSession): String {
            if (!settings.saveLogs) return ""
            val builder = StringBuilder()
            synchronized(notes) { notes.forEach { builder.append("[FFShare Deint] ").append(it).append('\n') } }
            builder.append(logText(session.getAllLogs()))
            return builder.toString()
        }

        // beginning and end of an ffmpeg output; one string of a multi-day output could exhaust the memory
        private fun logText(logs: List<com.arthenica.ffmpegkit.Log>): String {
            val builder = StringBuilder()
            if (logs.size <= LOG_HEAD_LINES + LOG_TAIL_LINES) {
                logs.forEach { builder.append(it.message) }
            } else {
                logs.subList(0, LOG_HEAD_LINES).forEach { builder.append(it.message) }
                builder.append("\n[... ${logs.size - LOG_HEAD_LINES - LOG_TAIL_LINES} lines cut ...]\n")
                logs.subList(logs.size - LOG_TAIL_LINES, logs.size).forEach { builder.append(it.message) }
            }
            return builder.toString()
        }

        private fun commandsText(): String = synchronized(prettyCommands) { prettyCommands.joinToString("\n") }

        /** Last "frame number,time" line of the encoder stats file of pass 1, null if none yet */
        private fun readEncoderProgress(): Pair<Long, Double>? {
            return try {
                val length = progressFile.length()
                if (length <= 0) return null
                RandomAccessFile(progressFile, "r").use { file ->
                    val start = (length - 256).coerceAtLeast(0)
                    val bytes = ByteArray((length - start).toInt())
                    file.seek(start)
                    file.readFully(bytes)
                    // complete lines only: the part after the last newline may still be written
                    val line = String(bytes, Charsets.US_ASCII).split('\n').dropLast(1).lastOrNull { it.contains(',') }
                        ?: return null
                    val frames = line.substringBefore(',').trim().toLongOrNull() ?: return null
                    val seconds = line.substringAfter(',').trim().toDoubleOrNull() ?: return null
                    Pair(frames, seconds)
                }
            } catch (e: IOException) {
                null
            }
        }

        private fun lastLogLines(session: FFmpegSession, count: Int): String {
            val logs = session.getAllLogs()
            val builder = StringBuilder()
            logs.subList((logs.size - count).coerceAtLeast(0), logs.size).forEach { builder.append(it.message) }
            return builder.toString()
        }

        private fun onStatistics(statistics: Statistics) {
            if (!showProgress || done.get()) return
            var timeMs = statistics.time
            var fps = statistics.videoFps
            var speed = statistics.speed
            if (encoderStats && currentPass >= 0) {
                // see progressFile: ffmpeg's own time is missing in pass 1 and with sparse subtitles
                readEncoderProgress()?.let { (frames, seconds) ->
                    val elapsed = (System.currentTimeMillis() - passStartedAt) / 1000.0
                    timeMs = seconds * 1000
                    if (elapsed > 0) {
                        fps = (frames / elapsed).toFloat()
                        speed = seconds / elapsed
                    }
                }
            }
            // "no timestamp yet" arrives as -0.0 (and would show as "-0.0 %")
            if (!(timeMs > 0)) timeMs = 0.0
            // the border detection scans the whole file, a test excerpt is shorter
            val stepMs = if (currentPass == STEP_BORDERS) fullDurationMs.toDouble() else durationMs.toDouble()
            val fraction = if (stepMs > 0) (timeMs / stepMs).coerceIn(0.0, 1.0) else 0.0
            // two-pass: analysis pass 0..50 %, encode pass 50..100 %; with the FFV1 step first, the
            // shares follow the measured run times (filtering, a quick analysis pass, the encode)
            val total = if (prefilterRan) {
                when (currentPass) {
                    STEP_PREFILTER -> fraction * SHARE_PREFILTER
                    1 -> SHARE_PREFILTER + fraction * SHARE_PASS1_AFTER_PREFILTER
                    2 -> SHARE_PREFILTER + SHARE_PASS1_AFTER_PREFILTER + fraction * (1 - SHARE_PREFILTER - SHARE_PASS1_AFTER_PREFILTER)
                    else -> fraction
                }
            } else {
                when (currentPass) {
                    1 -> fraction / 2
                    2 -> 0.5 + fraction / 2
                    else -> fraction
                }
            }
            if (currentPass == STEP_PREFILTER) checkPrefilterSpace(statistics.size, fraction)
            val etaSeconds = if (speed > 0.0 && stepMs > 0 && timeMs > 0) ((stepMs - timeMs) / 1000.0 / speed).toLong().coerceAtLeast(0) else -1L
            val status = statusLine(fps, etaSeconds)
            val pass = currentPass
            ui {
                views.txtProcessedPercent.text = context.getString(R.string.format_percentage, (total * 100).toFloat())
                views.txtProcessedTime.text = utils.millisToMicrowaveTime(timeMs.toInt())
                if (pass == 0 || pass == 2) {
                    views.txtOutputFileSize.text = utils.bytesToHuman(statistics.size)
                }
                views.txtCommandNumber.text = status
            }
            val now = System.currentTimeMillis()
            if (keepAliveActive && now - lastNotification >= NOTIFICATION_INTERVAL_MS) {
                lastNotification = now
                val percent = String.format(Locale.US, "%.1f %%", total * 100)
                EncodeService.update(context, "$inputFileName\n$percent · $status", (total * 1000).toInt())
            }
        }

        private fun showStage() {
            val status = statusLine(0f, -1)
            ui { views.txtCommandNumber.text = status }
            if (keepAliveActive) {
                EncodeService.update(context, "$inputFileName\n$status", -1)
            }
        }

        private fun statusLine(fps: Float, etaSeconds: Long): String {
            val parts = ArrayList<String>()
            if (fileLabel.isNotEmpty()) parts.add(fileLabel)
            if (stage.isNotEmpty()) parts.add(stage)
            if (fps > 0f) parts.add(String.format(Locale.US, "%.2f fps", fps))
            if (etaSeconds >= 0) parts.add(context.getString(R.string.status_eta, Utils.formatDuration(etaSeconds)))
            parts.add(context.getString(R.string.status_elapsed, Utils.formatDuration((System.currentTimeMillis() - startedAt) / 1000)))
            return parts.joinToString(" · ")
        }

        private fun isLongRun(): Boolean {
            return keepAliveActive && System.currentTimeMillis() - startedAt >= RESULT_NOTIFICATION_AFTER_MS
        }

        // name of the saved copy: input name with the new extension; test excerpts carry their settings
        private fun copyName(): String {
            val extension = outputFile.extension
            val base = inputFileName.substringBeforeLast('.')
            return if (excerptParams.isNotEmpty()) {
                "$base [test ${ffmpegParamMaker.settingsLabel(mediaType, outputMediaType)}].$extension"
            } else {
                "$base.$extension"
            }
        }
    }

    companion object {
        /** A conversion is running in this process (one at a time; read by CacheCleanUpReceiver) */
        @Volatile
        var busy = false

        /**
         * Input copies, pass logs and unfinished outputs of conversions that were killed. Only call when
         * no conversion runs: at process start (App) or at the start of a batch.
         */
        fun removeLeftovers(context: Context) {
            for (name in arrayOf(INPUT_DIR, WORK_DIR, PASSLOG_DIR)) {
                File(context.noBackupFilesDir, name).listFiles()?.forEach { it.delete() }
            }
        }

        private const val INPUT_DIR = "input"
        private const val WORK_DIR = "work"
        private const val PASSLOG_DIR = "twopass"
        private const val STEP_COPY = -2
        private const val STEP_BORDERS = -1
        // >= 0 like the passes: its progress comes from the encoder statistics as well
        private const val STEP_PREFILTER = 3
        // share of the progress bar for the filter-once step and the analysis pass reading the intermediate
        // (measured with the anime preset: filtering ~20 %, analysis ~4 %, encode ~76 % of the time)
        private const val SHARE_PREFILTER = 0.2
        private const val SHARE_PASS1_AFTER_PREFILTER = 0.05
        // free storage the filter-once step always leaves (output, pass logs, the phone itself)
        private const val PREFILTER_MIN_FREE = 1024L * 1024 * 1024
        private const val CONFIRM_CANCEL_AFTER_MS = 60_000L
        private const val NOTIFICATION_INTERVAL_MS = 5_000L
        private const val RESULT_NOTIFICATION_AFTER_MS = 2 * 60_000L
        // free space that has to stay after a private copy of the input (output, pass logs, the phone)
        private const val COPY_SPACE_RESERVE = 1024L * 1024 * 1024
        private const val LOG_HEAD_LINES = 300
        private const val LOG_TAIL_LINES = 3000
    }
}
