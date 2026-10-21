package com.caydey.ffshare.utils

import android.net.Uri
import com.arthenica.ffmpegkit.MediaInformation
import timber.log.Timber
import java.util.Locale
import java.util.StringJoiner
import kotlin.math.ceil
import kotlin.math.roundToInt

class FFmpegParamMaker(val settings: Settings, val utils: Utils) {

    /** Result of the black border detection, arguments of ffmpeg's crop filter */
    data class Crop(val width: Int, val height: Int, val x: Int, val y: Int) {
        val filter: String get() = "crop=$width:$height:$x:$y"
    }

    // the MediaCodec list does not change while the app runs
    private val av1HardwareName: String? by lazy { utils.hardwareAv1EncoderName() }

    /**
     * Audio tracks picked in the dialog when sharing (0-based audio stream indices; empty = no audio),
     * null = use the "Audio track" setting. Set by MediaCompressor for a whole batch.
     */
    @Volatile
    var audioTrackSelection: List<Int>? = null

    /**
     * Subtitle tracks picked in the dialog when sharing (0-based subtitle stream indices; empty = none),
     * only used with "Subtitles: Ask when sharing" MKV -> MKV. Set by MediaCompressor for a whole batch.
     */
    @Volatile
    var subtitleTrackSelection: List<Int>? = null

    /**
     * pass:        0 = single run, 1 = analysis run of a two-pass encode (video only, output discarded),
     *              2 = final run of a two-pass encode. The caller adds "-pass N -passlogfile ..." itself.
     * crop:        result of the border detection run, null = no cropping
     * prefiltered: the input is the FFV1 intermediate (see prefilterChain): deinterlacing, cropping,
     *              denoising and scaling are done already; streams are in the same order as in the source
     */
    fun create(inputFile: Uri, mediaInformation: MediaInformation, mediaType: Utils.MediaType, outputMediaType: Utils.MediaType,
               pass: Int = 0, crop: Crop? = null, prefiltered: Boolean = false): String {
        // custom params
        if (utils.isImage(outputMediaType) && settings.customImageParams.isNotEmpty()) return settings.customImageParams
        if (utils.isVideo(outputMediaType) && settings.customVideoParams.isNotEmpty()) return settings.customVideoParams
        if (utils.isAudio(outputMediaType) && settings.customAudioParams.isNotEmpty()) return settings.customAudioParams

        val params = StringJoiner(" ")
        val videoFormatParams = StringJoiner(",")
        val videoConversion = isVideoConversion(mediaType, outputMediaType)
        val twoPassRun = pass != 0
        val twoPassKbps = if (twoPassRun) settings.twoPassBitrateKbps else 0

//        val (inputVideoCodec, inputAudioCodec) = getMediaCodecs(mediaInformation)

        // stream selection (audio track, subtitles); the analysis pass only reads the video
        if (videoConversion) {
            addStreamSelection(params, mediaType, outputMediaType, pass)
        }

        // preset, webp does not support this
        if (outputMediaType != Utils.MediaType.WEBP) {
            params.add("-preset ${settings.compressionPreset}")
        }

        if ((utils.isAudio(mediaType) || utils.isVideo(mediaType)) && pass != 1) {
            // add audio codec
            if (settings.audioCodec != Settings.AudioCodecOpts.DEFAULT) {
                params.add("-c:a ${settings.audioCodec.raw}")
            }
            addAudioParams(params)
        }

        // deinterlace, aspect, crop, denoise, scale; the FFV1 intermediate has all of that done already
        val (filters, resized) = if (prefiltered) Pair(emptyList<String>(), true)
                                 else sourceFilters(inputFile, mediaInformation, mediaType, outputMediaType, crop)
        filters.forEach { videoFormatParams.add(it) }

        // video
        if (utils.isVideo(outputMediaType)) { // check outputMediaType not mediaType because conversions
            val hardwareAv1 = if (settings.videoCodec == Settings.VideoCodecOpts.AV1) av1HardwareName else null
            val libaom = usesLibaom()

            // pixel format; 10 bit for libaom on request (less banding, often a little smaller)
            videoFormatParams.add(pixelFormatFilter())

            //  max file size (limit the bitrate to achieve this); a two-pass target bitrate replaces it
            var maxSizeKbps = 0
            if (settings.videoMaxFileSize != 0 && twoPassKbps == 0) {
                // ffmpeg does not like -maxrate & -bufsize params when the output file is webm
                if (outputMediaType != Utils.MediaType.WEBM) {
                    // Calculate maximum bitrate in kbps.
                    // Ceil duration to ensure the maximum is strict. toInt to floor result, ffmpeg takes ints.
                    val maxBitrate = (settings.videoMaxFileSize * 8 / ceil(effectiveDurationSeconds(mediaInformation, mediaType))).toInt()
                    Timber.d("Maximum bitrate for targeted filesize (%dK): %dk", settings.videoMaxFileSize, maxBitrate)

                    val audioBitrate: Int
                    if (settings.audioBitrateKbps > 0) {
                        // set by addAudioParams already
                        audioBitrate = settings.audioBitrateKbps
                    } else {
                        // audio can have at most one third of the total bitrate
                        val audioSplit = maxBitrate / 3
                        // round audio bitrate down to 192,128,96,64,32,24
                        audioBitrate = if (audioSplit > 192) 192 // maximum audio bitrate is 192k
                        else if (audioSplit > 128) 128
                        else if (audioSplit > 96) 96
                        else if (audioSplit > 64) 64
                        else if (audioSplit > 32) 32
                        else 24 // minimum audio bitrate is 24k

                        // set audio bitrate (not in the analysis pass, it has no encoded audio)
                        if (pass != 1) {
                            params.add("-b:a ${audioBitrate}k")
                        }
                    }
                    maxSizeKbps = (maxBitrate - audioBitrate).coerceAtLeast(1)
                }
            }

            // video codec + rate control
            when {
                hardwareAv1 != null -> {
                    // MediaCodec encoder: no CRF, VBR with a target bitrate; pin the hardware codec by name.
                    // -g 250: key frame interval 10 s at 25 fps (MediaCodec default would be 1 s)
                    val kbps = if (maxSizeKbps > 0) minOf(maxSizeKbps, settings.av1HardwareBitrateKbps) else settings.av1HardwareBitrateKbps
                    params.add("-c:v av1_mediacodec -codec_name $hardwareAv1 -bitrate_mode vbr -b:v ${kbps}k -g 250")
                }
                libaom -> params.add(libaomParams(twoPassKbps, twoPassRun, maxSizeKbps))
                twoPassRun && settings.videoCodec == Settings.VideoCodecOpts.VP9 -> {
                    // constant quality, or constrained quality under a size limit (VP9 rejects -maxrate
                    // without a bitrate)
                    params.add("-c:v libvpx-vp9")
                    params.add(if (twoPassKbps > 0) "-b:v ${twoPassKbps}k" else "-crf ${settings.videoCrf} -b:v ${maxSizeKbps}k")
                }
                twoPassRun && settings.videoCodec == Settings.VideoCodecOpts.H264 -> {
                    // x264 two-pass needs a target bitrate (usesTwoPass() only allows it with one)
                    params.add("-c:v libx264 -b:v ${twoPassKbps}k")
                }
                else -> {
                    params.add("-crf ${settings.videoCrf}")
                    if (settings.videoCodec != Settings.VideoCodecOpts.DEFAULT) {
                        params.add("-c:v ${settings.videoCodec.raw}")
                    }
                    if (maxSizeKbps > 0) {
                        if (settings.videoCodec == Settings.VideoCodecOpts.VP9) {
                            // libvpx: crf + bitrate = constrained quality (-maxrate alone is rejected)
                            params.add("-b:v ${maxSizeKbps}k")
                        } else {
                            // set max video bitrate
                            params.add("-maxrate ${maxSizeKbps}k -bufsize ${maxSizeKbps}k")
                        }
                    }
                }
            }

            // H.26x requires dimensions to be divisible by 2, video scaling and cropping account for this
            if (!resized) {
                evenSizeFilter(mediaInformation)?.let { videoFormatParams.add(it) }
            }
        }

        if (videoFormatParams.length() > 0) {
            params.add("-vf \"$videoFormatParams\"")
        }

        // jpeg quality
        if (outputMediaType == Utils.MediaType.JPEG || outputMediaType == Utils.MediaType.JPG) {
            params.add("-qscale:v ${settings.jpegQscale}")
        }

        return params.toString()
    }

    /**
     * Two-pass encodes with "Filter once (FFV1)": the filters run in one extra step into a lossless file
     * that both passes read, instead of filtering the source in each pass
     */
    fun usesPrefilter(mediaType: Utils.MediaType, outputMediaType: Utils.MediaType): Boolean {
        return settings.prefilterIntermediate && usesTwoPass(mediaType, outputMediaType)
    }

    /**
     * -vf of the FFV1 intermediate: the chain the passes would run on the source, without the final pixel
     * format conversion (the passes still do that). The intermediate keeps the filters' own format, 8 bit
     * for DVDs: about 40 % smaller than 10 bit, and the encoder gets practically the same frames (only the
     * scaler's extra precision in the last bit of the 10-bit samples is rounded away, about 60 dB PSNR).
     * Null when there is nothing to filter (then the intermediate would only cost time and space).
     */
    fun prefilterChain(inputFile: Uri, mediaInformation: MediaInformation, mediaType: Utils.MediaType,
                       outputMediaType: Utils.MediaType, crop: Crop?): String? {
        val (filters, resized) = sourceFilters(inputFile, mediaInformation, mediaType, outputMediaType, crop)
        if (filters.isEmpty()) return null
        val chain = ArrayList(filters)
        if (!resized) evenSizeFilter(mediaInformation)?.let { chain.add(it) }
        return chain.joinToString(",")
    }

    /**
     * Output options of the FFV1 intermediate: the filtered video plus every audio track, subtitle track and
     * attachment copied unchanged, so the passes find all streams in the same order as in the source.
     * FFV1 level 3 with slices (multithreaded), no per-slice CRC (the file lives only for this job).
     */
    fun prefilterParams(chain: String): String {
        return "-map 0:v:0 -map 0:a? -map 0:s? -map 0:t? -vf \"$chain\" " +
                "-c:v ffv1 -level 3 -slices 4 -slicecrc 0 -c:a copy -c:s copy -f matroska"
    }

    /**
     * Deinterlace (fields must be separated before anything scales the picture), display aspect, crop,
     * denoise (at full resolution), scale - everything before the pixel format. Second value: the size
     * was changed (scaled or cropped), so it is even already.
     */
    private fun sourceFilters(inputFile: Uri, mediaInformation: MediaInformation, mediaType: Utils.MediaType,
                              outputMediaType: Utils.MediaType, crop: Crop?): Pair<List<String>, Boolean> {
        val filters = ArrayList<String>()
        val videoConversion = isVideoConversion(mediaType, outputMediaType)
        if (videoConversion && settings.deinterlace) {
            filters.add(deinterlaceFilter())
            val aspect = settings.deinterlaceAspect
            if (aspect != Settings.DeinterlaceAspectOpts.SOURCE) {
                // analogue SD sources often carry no aspect information; setdar fixes the
                // display aspect without touching the pixels
                filters.add("setdar=${aspect.dar}")
            }
        }
        if (videoConversion && crop != null) {
            // crop keeps the pixel aspect ratio, so the display aspect of the rest stays correct
            filters.add(crop.filter)
        }
        if (videoConversion) {
            denoiseFilter()?.let { filters.add(it) }
        }
        var videoScaleApplied = false
        if (videoConversion && settings.deinterlace && settings.deinterlaceUpscale1080) {
            // square pixels at 1080 lines, width from the display aspect (4:3 -> 1440, 16:9 -> 1920),
            // rounded to an even number for H.26x
            filters.add("scale=trunc(dar*1080/2)*2:1080:flags=lanczos")
            filters.add("setsar=1")
            videoScaleApplied = true
        }

        // videos and images max resolution
        if ((utils.isVideo(mediaType) || utils.isImage(mediaType)) && !videoScaleApplied) {
            val (sourceWidth, sourceHeight) = if (utils.isVideo(mediaType)) videoSize(inputFile, mediaInformation)
                                              else utils.getMediaResolution(inputFile, mediaType)
            // the scaler sees the cropped picture
            val resolutionWidth = crop?.width ?: sourceWidth
            val resolutionHeight = crop?.height ?: sourceHeight
            val isPortrait = resolutionHeight > resolutionWidth
            val resolution = if (isPortrait) resolutionWidth else resolutionHeight
            val maxResolution = if (utils.isImage(mediaType)) settings.maxImageResolution else settings.maxVideoResolution
            // only reduce resolution
            if (resolution > maxResolution && maxResolution != 0) {
                videoScaleApplied = true
                if (utils.isVideo(mediaType)) {
                    // display aspect aware: anamorphic sources (DVD, DV, 720x576 captures) get square pixels at
                    // the right width (PAL 4:3 at 480 lines -> 640x480) instead of being squeezed; even sizes
                    if (isPortrait) {
                        filters.add("scale=$maxResolution:trunc($maxResolution/dar/2)*2")
                    } else {
                        filters.add("scale=trunc(dar*$maxResolution/2)*2:$maxResolution")
                    }
                    filters.add("setsar=1")
                } else {
                    var pixelRounding = "-1"
                    // H.26x videos require dimensions to be divisible by 2
                    if (utils.isVideo(outputMediaType) && settings.videoCodec in setOf(Settings.VideoCodecOpts.H264, Settings.VideoCodecOpts.H265, Settings.VideoCodecOpts.AV1, Settings.VideoCodecOpts.AV1_SOFTWARE)) {
                        pixelRounding = "-2"
                    }
                    if (isPortrait) {
                        filters.add("scale=$maxResolution:$pixelRounding,setsar=1")
                    } else {
                        filters.add("scale=$pixelRounding:$maxResolution,setsar=1")
                    }
                }
            }
        }
        return Pair(filters, videoScaleApplied || crop != null)
    }

    // pixel format of video outputs; 10 bit for libaom on request (less banding, often a little smaller)
    private fun pixelFormatFilter(): String = if (usesLibaom() && settings.av1TenBit) "format=yuv420p10le" else "format=yuv420p"

    // H.26x requires dimensions divisible by 2: an odd source loses its last column/row (when nothing scales)
    private fun evenSizeFilter(mediaInformation: MediaInformation): String? {
        val stream = mediaInformation.getStreams().firstOrNull { it.getType()?.lowercase() == "video" }
            ?: mediaInformation.getStreams().firstOrNull()
        return if (stream?.getWidth()?.rem(2) != 0L || stream?.getHeight()?.rem(2) != 0L) {
            // could also use "pad=ceil(iw/2)*2:ceil(ih/2)*2" to add column/row of black pixels
            "crop=trunc(iw/2)*2:trunc(ih/2)*2"
        } else null
    }

    /**
     * True when the conversion runs as two ffmpeg passes: libaom AV1 and VP9 always (with CRF the first
     * pass still improves the bit distribution), x264 only with a target bitrate. Not for the hardware
     * AV1 encoder, H.265 or custom parameters.
     */
    fun usesTwoPass(mediaType: Utils.MediaType, outputMediaType: Utils.MediaType): Boolean {
        if (!settings.twoPass || !isVideoConversion(mediaType, outputMediaType) || settings.customVideoParams.isNotEmpty()) {
            return false
        }
        return when (settings.videoCodec) {
            Settings.VideoCodecOpts.AV1, Settings.VideoCodecOpts.AV1_SOFTWARE -> usesLibaom()
            Settings.VideoCodecOpts.VP9 -> true
            Settings.VideoCodecOpts.H264 -> settings.twoPassBitrateKbps > 0
            else -> false
        }
    }

    /** The video is encoded by our own parameters: per-frame encoder statistics give the progress */
    fun usesEncoderStats(mediaType: Utils.MediaType, outputMediaType: Utils.MediaType): Boolean {
        return isVideoConversion(mediaType, outputMediaType) && settings.customVideoParams.isEmpty() &&
                settings.videoCodec != Settings.VideoCodecOpts.COPY
    }

    /** Encodes that run for hours (progress is reported less often then) */
    fun isSlowEncode(mediaType: Utils.MediaType, outputMediaType: Utils.MediaType): Boolean {
        if (!isVideoConversion(mediaType, outputMediaType) || settings.customVideoParams.isNotEmpty()) return false
        return usesTwoPass(mediaType, outputMediaType) || (usesLibaom() && settings.av1SoftwareSpeed < 8) ||
                settings.denoise.startsWith("nlmeans")
    }

    /** True when black borders should be detected before the conversion */
    fun wantsCropDetect(mediaType: Utils.MediaType, outputMediaType: Utils.MediaType): Boolean {
        return settings.autoCrop && isVideoConversion(mediaType, outputMediaType) && settings.customVideoParams.isEmpty()
    }

    // Border detection run: only key frames are decoded, so the whole video is scanned in a short time.
    // reset=0 keeps the union of all frames (a dark scene can't shrink the area), round=2 keeps 4:2:0 sizes.
    val cropDetectInputParams = "-skip_frame nokey"
    val cropDetectParams = "-map 0:v:0 -vf cropdetect=round=2:reset=0 -an -sn -dn -f null -"

    /** Last "crop=w:h:x:y" of a cropdetect log, null if there is nothing (plausible) to crop */
    fun parseCropDetect(log: String?, mediaInformation: MediaInformation): Crop? {
        if (log == null) return null
        val match = Regex("crop=(\\d+):(\\d+):(\\d+):(\\d+)").findAll(log).lastOrNull() ?: return null
        val values = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
        val (width, height, x, y) = values
        val (sourceWidth, sourceHeight) = videoStreamSize(mediaInformation)
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        if (width == sourceWidth && height == sourceHeight) return null
        // more than half of the picture "black" means a dark video, not borders
        if (width < sourceWidth / 2 || height < sourceHeight / 2 || x + width > sourceWidth || y + height > sourceHeight) {
            Timber.d("Ignoring implausible crop %dx%d+%d+%d for %dx%d", width, height, x, y, sourceWidth, sourceHeight)
            return null
        }
        return Crop(width, height, x, y)
    }

    /** Start and length (seconds) of the test excerpt, null = whole video */
    fun excerpt(mediaInformation: MediaInformation, mediaType: Utils.MediaType): Pair<Double, Int>? {
        val length = settings.testExcerptSeconds
        if (length <= 0 || !utils.isVideo(mediaType)) return null
        val duration = mediaInformation.getDuration()?.toDoubleOrNull() ?: return null
        if (duration <= length) return null
        // 40 % into the video: past opening titles, still before the end credits
        return Pair((duration - length) * 0.4, length)
    }

    /** Input options (before -i) for the test excerpt, empty for the whole video */
    fun excerptInputParams(mediaInformation: MediaInformation, mediaType: Utils.MediaType): String {
        val (start, length) = excerpt(mediaInformation, mediaType) ?: return ""
        return String.format(Locale.US, "-ss %.3f -t %d", start, length)
    }

    /** Duration of what is actually converted (the excerpt, or the whole file) */
    fun effectiveDurationSeconds(mediaInformation: MediaInformation, mediaType: Utils.MediaType): Double {
        excerpt(mediaInformation, mediaType)?.let { return it.second.toDouble() }
        return mediaInformation.getDuration()?.toDoubleOrNull() ?: 0.0
    }

    /** Short description of the rate control, used to name saved test excerpts ("150k s2") */
    fun settingsLabel(mediaType: Utils.MediaType, outputMediaType: Utils.MediaType): String {
        val parts = ArrayList<String>()
        if (usesTwoPass(mediaType, outputMediaType) && settings.twoPassBitrateKbps > 0) {
            parts.add("${settings.twoPassBitrateKbps}k")
        } else {
            parts.add("crf${if (usesLibaom()) av1Crf() else settings.videoCrf}")
        }
        if (usesLibaom()) parts.add("s${settings.av1SoftwareSpeed}")
        if (settings.denoise != "off") parts.add(settings.denoise)
        return parts.joinToString(" ")
    }

    private fun isVideoConversion(mediaType: Utils.MediaType, outputMediaType: Utils.MediaType): Boolean {
        return utils.isVideo(mediaType) && utils.isVideo(outputMediaType)
    }

    // libaom is used for "AV1 software" and for "AV1" on devices without a hardware AV1 encoder
    private fun usesLibaom(): Boolean {
        return settings.videoCodec == Settings.VideoCodecOpts.AV1_SOFTWARE ||
                (settings.videoCodec == Settings.VideoCodecOpts.AV1 && av1HardwareName == null)
    }

    // x264-style CRF list 0..51 spread over libaom's 0..63 (23 -> 28, 40 -> 49, 51 -> 63)
    private fun av1Crf(): Int = (settings.videoCrf * 63f / 51f).roundToInt().coerceIn(0, 63)

    private fun libaomParams(targetKbps: Int, twoPassRun: Boolean, maxSizeKbps: Int): String {
        // a bitrate without crf = VBR (two-pass: close to the target size, short simple clips may stay below); crf + -b:v 0 = constant quality;
        // crf + a bitrate = constrained quality, used for the size limit (libaom rejects -maxrate there)
        val rateControl = when {
            targetKbps > 0 -> "-b:v ${targetKbps}k"
            maxSizeKbps > 0 -> "-crf ${av1Crf()} -b:v ${maxSizeKbps}k"
            else -> "-crf ${av1Crf()} -b:v 0"
        }
        val speed = settings.av1SoftwareSpeed
        val mode = if (speed >= 8 && !twoPassRun) {
            // realtime usage: no look-ahead, the only libaom mode a phone handles at a few fps
            "-usage realtime -cpu-used 8 -row-mt 1 -tiles 2x2"
        } else {
            // good usage: 48 frames look-ahead (libaom's maximum) for alt-ref frames and temporal filtering;
            // no tiles (they cost compression), row-mt keeps several cores busy anyway
            "-usage good -cpu-used $speed -lag-in-frames 48 -row-mt 1"
        }
        // a key frame at least every 250 frames (10 s at 25 fps) keeps seeking fast; cuts get their own
        return "-c:v libaom-av1 $rateControl $mode -g 250"
    }

    private fun addStreamSelection(params: StringJoiner, mediaType: Utils.MediaType, outputMediaType: Utils.MediaType, pass: Int) {
        val track = settings.audioTrack
        val selection = audioTrackSelection
        // only MKV -> MKV: every subtitle format of the source can be copied as it is
        val mkvToMkv = mediaType == Utils.MediaType.MKV && outputMediaType == Utils.MediaType.MKV
        val copySubtitles = settings.subtitles == "copy" && mkvToMkv
        val pickSubtitles = settings.subtitles == "ask" && mkvToMkv
        val explicitMaps = selection != null || track != 0 || copySubtitles || pickSubtitles
        if (pass == 1) {
            // Analysis pass: only the same video stream as in pass 2 (explicit -map, or ffmpeg's own
            // choice), which must be output stream 0 in both passes - the pass log is named after the
            // stream index (prefix-0.log). Progress comes from -stats_enc_pre (MediaCompressor).
            params.add(if (explicitMaps) "-map 0:v:0" else "-an -sn -dn")
            return
        }
        if (explicitMaps) {
            params.add("-map 0:v:0")
            when {
                // ticked in the dialog; "?" because later files of a batch may have fewer tracks
                selection != null -> selection.forEach { params.add("-map 0:a:$it?") }
                track > 0 -> params.add("-map 0:a:${track - 1}?")
                track == Settings.AUDIO_TRACK_ALL -> params.add("-map 0:a?")
                // ffmpeg's choice together with copied subtitles, or "ask" for a file with one track
                else -> params.add("-map 0:a:0?")
            }
            if (copySubtitles) {
                // all subtitle tracks plus attachments (fonts of ASS subtitles)
                params.add("-map 0:s? -c:s copy -map 0:t?")
            } else if (pickSubtitles) {
                // the ticked tracks in their order; "?" because later files of a batch may have fewer
                val subtitles = subtitleTrackSelection.orEmpty()
                if (subtitles.isNotEmpty()) {
                    subtitles.forEach { params.add("-map 0:s:$it?") }
                    params.add("-c:s copy -map 0:t?")
                }
            }
        } else if (settings.subtitles == "none") {
            params.add("-sn")
        }
    }

    private fun addAudioParams(params: StringJoiner) {
        val codec = settings.audioCodec
        if (codec == Settings.AudioCodecOpts.COPY) return
        val kbps = settings.audioBitrateKbps
        if (kbps > 0 && codec != Settings.AudioCodecOpts.FLAC) {
            params.add("-b:a ${kbps}k")
        }
        when (settings.audioChannels) {
            1 -> params.add("-ac 1")
            2 -> params.add("-ac 2")
        }
        if (codec == Settings.AudioCodecOpts.OPUS) {
            if (settings.audioChannels == 0) {
                // libopus only accepts Vorbis channel layouts; DVD AC-3 5.1 decodes as 5.1(side) and
                // would abort the encode -> let ffmpeg remap to the nearest allowed layout
                params.add("-af aformat=channel_layouts=7.1|5.1|stereo|mono")
            }
            if (kbps in 1..64) {
                // 60 ms packets instead of 20 ms: a third of the per-packet container overhead,
                // worth 1-3 kbit/s at these bitrates
                params.add("-frame_duration 60")
            }
        }
    }

    // bwdif = "Bob Weaver Deinterlacing Filter", motion adaptive, keeps still areas at full resolution.
    // mode:   send_field -> one output frame per field (576i50 -> 576p50), send_frame -> 25p/30p
    // parity: auto -> field order from the stream flags, tff/bff -> forced
    // deint:  interlaced -> only frames the decoder flagged as interlaced (progressive videos pass
    //         through untouched), all -> every frame
    // fieldmatch (film/anime): rebuilds progressive frames from matching fields (PAL 2:2 pulldown, also
    //         field-shifted DVDs) without any loss; frames that still comb are flagged as interlaced and
    //         only those go through bwdif. Always one frame per frame (25p).
    private fun deinterlaceFilter(): String {
        val parity = when (settings.deinterlaceParity) {
            "tff" -> "tff"
            "bff" -> "bff"
            else -> "auto"
        }
        if (settings.deinterlaceMethod == "fieldmatch") {
            return "fieldmatch=order=$parity:combmatch=full,bwdif=mode=send_frame:parity=$parity:deint=interlaced"
        }
        val mode = if (settings.deinterlaceMode == "send_frame") "send_frame" else "send_field"
        val deint = if (settings.deinterlaceFrames == "all") "all" else "interlaced"
        return "bwdif=mode=$mode:parity=$parity:deint=$deint"
    }

    // Denoising before the encoder: DVD noise and MPEG-2 mosquito noise around line art would otherwise
    // eat most of a small bitrate. hqdn3d is fast (spatial + temporal), nlmeans keeps edges/line art better.
    private fun denoiseFilter(): String? {
        return when (settings.denoise) {
            "hqdn3d" -> "hqdn3d=2:1.5:3:2.25"
            "nlmeans" -> "nlmeans=s=2:p=7:r=9"
            "nlmeans_strong" -> "nlmeans=s=4:p=7:r=9"
            else -> null
        }
    }

    /** Width/height of the first video stream (ffprobe), MediaMetadataRetriever as fallback */
    private fun videoSize(inputFile: Uri, mediaInformation: MediaInformation): Pair<Int, Int> {
        val size = videoStreamSize(mediaInformation)
        if (size.first > 0 && size.second > 0) return size
        return utils.getMediaResolution(inputFile, Utils.MediaType.MP4)
    }

    private fun videoStreamSize(mediaInformation: MediaInformation): Pair<Int, Int> {
        val stream = mediaInformation.getStreams().firstOrNull { it.getType()?.lowercase() == "video" }
        return Pair(stream?.getWidth()?.toInt() ?: 0, stream?.getHeight()?.toInt() ?: 0)
    }

    private fun getMediaCodecs(mediaInformation: MediaInformation): Pair<Settings.VideoCodecOpts?, Settings.AudioCodecOpts?> {
        var audioCodec: Settings.AudioCodecOpts? = null
        var videoCodec: Settings.VideoCodecOpts? = null
        for (stream in mediaInformation.getStreams()) {
            if (stream.getType()?.lowercase() == "video") {
                videoCodec = Settings.VideoCodecOpts.parseCodec(stream.getCodec());
            } else if (stream.getType()?.lowercase() == "audio") {
                audioCodec = Settings.AudioCodecOpts.parseCodec((stream.getCodec()))
            }
        }
        return Pair(videoCodec, audioCodec)
    }
}
