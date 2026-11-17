package io.rrtuyan.selma

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.Log
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.StatisticsCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Builds FFmpeg argument lists and wraps async execution as a cancellable coroutine. */
object FFmpegEngine {

    /** Probe media duration in milliseconds. Returns null on failure. Blocking — call on IO. */
    fun probeDurationMs(path: String): Long? = runCatching {
        val info = FFprobeKit.getMediaInformation(path).getMediaInformation() ?: return null
        info.getDuration()?.toDoubleOrNull()?.let { (it * 1000).toLong() }
    }.getOrNull()

    fun probeFormat(path: String): String? = runCatching {
        FFprobeKit.getMediaInformation(path).getMediaInformation()?.getFormat()
    }.getOrNull()

    /** Probe source (videoBitrateKbps, audioBitrateKbps). Null where unknown. */
    fun probeBitrates(path: String): Pair<Int?, Int?> = runCatching {
        val info = FFprobeKit.getMediaInformation(path).getMediaInformation() ?: return@runCatching null to null
        var videoKbps: Int? = null
        var audioKbps: Int? = null
        for (stream in info.getStreams()) {
            val type = stream.getStringProperty("codec_type") ?: continue
            val br = stream.getBitrate()?.toLongOrNull() ?: continue
            val kbps = (br / 1000).toInt()
            if (type == "video" && videoKbps == null) videoKbps = kbps
            if (type == "audio" && audioKbps == null) audioKbps = kbps
        }
        if (videoKbps == null) {
            videoKbps = info.getBitrate()?.toLongOrNull()?.let { (it / 1000).toInt() }
        }
        videoKbps to audioKbps
    }.getOrNull() ?: (null to null)

    /**
     * Build the argument array (without the leading "ffmpeg") for a video conversion.
     * [videoBitrateKbps] / [audioBitrateKbps] <= 0 falls back to the codec default.
     * [useHardware] selects the MediaCodec GPU encoder when the codec offers one.
     */
    fun buildVideoCommand(
        input: String,
        output: String,
        videoCodec: VideoCodec,
        videoBitrateKbps: Int,
        includeAudio: Boolean,
        audioCodec: VideoAudioCodec,
        audioBitrateKbps: Int,
        sampleRate: Int?,
        channels: Int?,
        normalize: Boolean,
        useHardware: Boolean
    ): Array<String> {
        val args = mutableListOf("-y", "-i", input)

        val hw = useHardware && videoCodec.hardwareEncoder != null
        val encoder = if (hw) videoCodec.hardwareEncoder!! else videoCodec.encoder
        args += listOf("-c:v", encoder)
        val vbr = if (videoBitrateKbps > 0) videoBitrateKbps else videoCodec.defaultBitrateKbps
        args += listOf("-b:v", "${vbr}k")
        if (!hw) args += videoCodec.extraArgs

        if (includeAudio) {
            val resolved = resolveAudioForContainer(videoCodec.extension, audioCodec)
            args += listOf("-c:a", resolved.encoder)
            if (!isLosslessAudio(resolved)) {
                val abr = if (audioBitrateKbps > 0) audioBitrateKbps else resolved.defaultBitrateKbps
                args += listOf("-b:a", "${abr}k")
            }
            sampleRate?.let { args += listOf("-ar", it.toString()) }
            channels?.let { args += listOf("-ac", it.toString()) }
            if (normalize) args += listOf("-af", "loudnorm=I=-16:TP=-1.5:LRA=11")
        } else {
            args += "-an"
        }

        args += listOf("-movflags", "+faststart")
        args += output
        return args.toTypedArray()
    }

    /** Build the argument array for an audio-only conversion. */
    fun buildAudioCommand(
        input: String,
        output: String,
        audioCodec: AudioCodec,
        audioBitrateKbps: Int,
        sampleRate: Int?,
        channels: Int?,
        normalize: Boolean
    ): Array<String> {
        val args = mutableListOf("-y", "-i", input, "-vn", "-c:a", audioCodec.encoder)
        if (!audioCodec.lossless) {
            val abr = if (audioBitrateKbps > 0) audioBitrateKbps else audioCodec.defaultBitrateKbps
            args += listOf("-b:a", "${abr}k")
        }
        sampleRate?.let { args += listOf("-ar", it.toString()) }
        channels?.let { args += listOf("-ac", it.toString()) }
        if (normalize) args += listOf("-af", "loudnorm=I=-16:TP=-1.5:LRA=11")
        args += output
        return args.toTypedArray()
    }

    private fun isLosslessAudio(codec: VideoAudioCodec) = codec == VideoAudioCodec.FLAC

    /** Pick an audio codec actually muxable into the chosen video container. */
    private fun resolveAudioForContainer(containerExt: String, chosen: VideoAudioCodec): VideoAudioCodec {
        return when (containerExt) {
            "webm" -> if (chosen == VideoAudioCodec.OPUS || chosen == VideoAudioCodec.VORBIS) chosen else VideoAudioCodec.OPUS
            "mp4" -> if (chosen == VideoAudioCodec.VORBIS || chosen == VideoAudioCodec.FLAC) VideoAudioCodec.AAC else chosen
            else -> chosen
        }
    }

    /**
     * Execute an FFmpeg command asynchronously. [onProgressMs] is invoked on an
     * arbitrary thread with the current encoding time in milliseconds.
     */
    suspend fun execute(
        args: Array<String>,
        onProgressMs: (Long) -> Unit
    ): FFmpegSession = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeWithArgumentsAsync(
                args,
                object : com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback {
                    override fun apply(completed: FFmpegSession) {
                        if (cont.isActive) cont.resume(completed)
                    }
                },
                object : LogCallback { override fun apply(log: Log) {} },
                object : StatisticsCallback {
                    override fun apply(stats: Statistics) {
                        onProgressMs(stats.time.toLong())
                    }
                }
            )
            cont.invokeOnCancellation { FFmpegKit.cancel(session.getSessionId()) }
        }
    }
}
