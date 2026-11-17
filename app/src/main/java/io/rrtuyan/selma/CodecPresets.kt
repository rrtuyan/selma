package io.rrtuyan.selma

/**
 * Video output codecs. [encoder] is the FFmpeg software encoder name, [hardwareEncoder]
 * the Android MediaCodec equivalent when available, [extension] the container,
 * [defaultBitrateKbps] a sensible default bitrate and [extraArgs] encoder-specific
 * quality/speed flags inserted before the output path.
 */
enum class VideoCodec(
    val display: String,
    val encoder: String,
    val hardwareEncoder: String?,
    val extension: String,
    val defaultBitrateKbps: Int,
    val extraArgs: List<String>,
    val experimentalCrashRisk: Boolean = false
) {
    H264("H.264 / AVC", "libx264", "h264_mediacodec", "mp4", 2500, listOf("-preset", "medium")),
    H265("H.265 / HEVC", "libx265", "hevc_mediacodec", "mp4", 1800, listOf("-preset", "medium", "-tag:v", "hvc1"), experimentalCrashRisk = true),
    H266("H.266 / VVC", "libvvenc", null, "mkv", 1800, emptyList(), experimentalCrashRisk = true),
    VP8("VP8 (WebM)", "libvpx", null, "webm", 2500, listOf("-deadline", "good", "-cpu-used", "2")),
    VP9("VP9 (WebM)", "libvpx-vp9", null, "webm", 1800, listOf("-deadline", "good", "-cpu-used", "2")),
    AV1("AV1 (SVT)", "libsvtav1", null, "mkv", 1800, listOf("-preset", "6"));

    companion object {
        val displays = entries.map { it.display }
        fun fromDisplay(s: String) = entries.first { it.display == s }
    }
}

/**
 * Audio output codecs. [lossless] codecs ignore the bitrate setting.
 */
enum class AudioCodec(
    val display: String,
    val encoder: String,
    val extension: String,
    val defaultBitrateKbps: Int,
    val lossless: Boolean = false
) {
    AAC_LC("AAC-LC (.aac)", "aac", "aac", 128),
    M4A("M4A (AAC in MP4)", "aac", "m4a", 128),
    OPUS("Opus", "libopus", "opus", 128),
    MP3("MP3", "libmp3lame", "mp3", 192),
    VORBIS("Ogg Vorbis", "libvorbis", "ogg", 128),
    FLAC("FLAC (lossless)", "flac", "flac", 0, lossless = true),
    WAV_PCM("WAV / PCM 16-bit", "pcm_s16le", "wav", 0, lossless = true);

    companion object {
        val displays = entries.map { it.display }
        fun fromDisplay(s: String) = entries.first { it.display == s }
    }
}

/** Audio codecs offered as the audio track inside a video container. */
enum class VideoAudioCodec(
    val display: String,
    val encoder: String,
    val defaultBitrateKbps: Int
) {
    AAC("AAC", "aac", 128),
    OPUS("Opus", "libopus", 128),
    MP3("MP3", "libmp3lame", 192),
    VORBIS("Vorbis", "libvorbis", 128),
    FLAC("FLAC", "flac", 0);

    companion object {
        val displays = entries.map { it.display }
        fun fromDisplay(s: String) = entries.first { it.display == s }
    }
}

object SampleRates {
    /** "Original" = keep source. Values displayed in kHz. */
    val options = listOf("Original", "8 kHz", "16 kHz", "22.05 kHz", "32 kHz", "44.1 kHz", "48 kHz", "96 kHz")
    private val mapping = mapOf(
        "8 kHz" to 8000, "16 kHz" to 16000, "22.05 kHz" to 22050, "32 kHz" to 32000,
        "44.1 kHz" to 44100, "48 kHz" to 48000, "96 kHz" to 96000
    )
    fun valueOf(display: String): Int? = mapping[display]
}

object AudioBitrates {
    /** First entry "Same as source" = probe & reuse source bitrate. */
    val options = listOf("Same as source", "32 kbps", "64 kbps", "96 kbps", "128 kbps", "192 kbps", "256 kbps", "320 kbps")
    /** Returns kbps, or null when "Same as source". */
    fun kbpsOf(display: String): Int? = if (display == "Same as source") null else display.removeSuffix(" kbps").toInt()
}

object Channels {
    val options = listOf("Original", "Mono (1)", "Stereo (2)")
    fun valueOf(display: String): Int? = when (display) {
        "Mono (1)" -> 1
        "Stereo (2)" -> 2
        else -> null
    }
}

object VideoBitrates {
    /** First entry "Same as source" = probe & reuse source bitrate. */
    val options = listOf("Same as source", "500 kbps", "1000 kbps", "2000 kbps", "4000 kbps", "6000 kbps", "8000 kbps", "12000 kbps")
    /** Returns kbps, or null when "Same as source". */
    fun kbpsOf(display: String): Int? = if (display == "Same as source") null else display.removeSuffix(" kbps").toInt()
}
