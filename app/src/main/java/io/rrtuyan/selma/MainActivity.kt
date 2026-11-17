package io.rrtuyan.selma

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.MediaController
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import io.rrtuyan.selma.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var inputFile: File? = null
    private var durationMs: Long = 0L
    private var sourceVideoBitrateKbps: Int? = null
    private var sourceAudioBitrateKbps: Int? = null
    private var outputFile: File? = null

    /** Guards against stale completion callbacks after cancel/restart. */
    private var conversionGen = 0
    private var isConverting = false
    private var lastTabPosition = 0

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onFilePicked(it) }
    }

    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let { writeOutputTo(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Material 3 Expressive: dynamic color (Android 12+) applied before inflation
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val mc = MediaController(this)
        mc.setAnchorView(binding.previewVideo)
        binding.previewVideo.setMediaController(mc)

        setupTabs()
        setupDropdowns()
        setupListeners()
        updateAudioSectionEnabled()
    }

    // ---------- UI setup ----------

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (isConverting && tab.position != lastTabPosition) {
                    // Block tab switches mid-conversion (known crash cause)
                    binding.tabLayout.getTabAt(lastTabPosition)?.select()
                    toast("Finish or cancel the current conversion first")
                    return
                }
                lastTabPosition = tab.position
                val isVideo = tab.position == 0
                binding.videoPanel.isVisible = isVideo
                binding.audioPanel.isVisible = !isVideo
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        binding.tabLayout.getTabAt(0)?.select()
    }

    private fun setupDropdowns() {
        fun setOptions(actvId: Int, options: List<String>) {
            val actv = findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(actvId)
            actv.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, options))
        }
        setOptions(R.id.videoCodecDropdown, VideoCodec.displays)
        setOptions(R.id.videoBitrateDropdown, VideoBitrates.options)
        setOptions(R.id.videoAudioCodecDropdown, VideoAudioCodec.displays)
        setOptions(R.id.videoAudioBitrateDropdown, AudioBitrates.options)
        setOptions(R.id.videoSampleRateDropdown, SampleRates.options)
        setOptions(R.id.videoChannelsDropdown, Channels.options)

        setOptions(R.id.audioCodecDropdown, AudioCodec.displays)
        setOptions(R.id.audioBitrateDropdown, AudioBitrates.options)
        setOptions(R.id.audioSampleRateDropdown, SampleRates.options)
        setOptions(R.id.audioChannelsDropdown, Channels.options)

        // defaults — "Same as source" for bitrates
        binding.videoCodecDropdown.setText(VideoCodec.H264.display, false)
        binding.videoBitrateDropdown.setText(VideoBitrates.options[0], false) // Same as source
        binding.videoAudioCodecDropdown.setText(VideoAudioCodec.AAC.display, false)
        binding.videoAudioBitrateDropdown.setText(AudioBitrates.options[0], false)
        binding.videoSampleRateDropdown.setText("Original", false)
        binding.videoChannelsDropdown.setText("Original", false)

        binding.audioCodecDropdown.setText(AudioCodec.AAC_LC.display, false)
        binding.audioBitrateDropdown.setText(AudioBitrates.options[0], false)
        binding.audioSampleRateDropdown.setText("Original", false)
        binding.audioChannelsDropdown.setText("Original", false)

        // Warn about codecs with known native-crash risk on selection
        binding.videoCodecDropdown.setOnItemClickListener { _, _, _, _ ->
            val vc = VideoCodec.fromDisplay(binding.videoCodecDropdown.text.toString())
            if (vc.experimentalCrashRisk) {
                Snackbar.make(binding.root,
                    "$vc software encoding is known to crash on some devices (e.g. MT6781). Try enabling GPU acceleration.",
                    Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun setupListeners() {
        binding.btnPickFile.setOnClickListener {
            val mime = if (binding.tabLayout.selectedTabPosition == 0)
                arrayOf("video/*", "audio/*") else arrayOf("audio/*", "video/*")
            pickFile.launch(mime)
        }
        binding.switchIncludeAudio.setOnCheckedChangeListener { _, _ -> updateAudioSectionEnabled() }
        binding.btnConvert.setOnClickListener { onConvertClicked() }
        binding.btnCancel.setOnClickListener { cancelConversion() }
        binding.btnShare.setOnClickListener { shareOutput() }
        binding.btnSaveTo.setOnClickListener { saveOutputTo() }
        binding.btnPlayOutput.setOnClickListener { playOutput() }
    }

    private fun updateAudioSectionEnabled() {
        binding.videoAudioSection.isVisible = binding.switchIncludeAudio.isChecked
    }

    // ---------- File picking + preview ----------

    private fun onFilePicked(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val tmp = File(cacheDir, "input_${System.currentTimeMillis()}.tmp")
            contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            inputFile = tmp
            durationMs = FFmpegEngine.probeDurationMs(tmp.absolutePath) ?: 0L
            val fmt = FFmpegEngine.probeFormat(tmp.absolutePath)
            val (vbr, abr) = FFmpegEngine.probeBitrates(tmp.absolutePath)
            sourceVideoBitrateKbps = vbr
            sourceAudioBitrateKbps = abr

            withContext(Dispatchers.Main) {
                binding.tvSelectedFile.text = "Selected: ${tmp.length() / 1024} KB"
                binding.tvFileInfo.text = buildString {
                    append("Format: ${fmt ?: "?"}")
                    if (durationMs > 0) append("   Duration: ${formatDuration(durationMs)}")
                    vbr?.let { append("   Video: ${it} kbps") }
                    abr?.let { append("   Audio: ${it} kbps") }
                }
                binding.btnConvert.isEnabled = true
                loadPreview(tmp.absolutePath, "Input preview")
            }
        }
    }

    private fun loadPreview(path: String, label: String) {
        binding.tvPreviewLabel.text = label
        binding.tvPreviewLabel.isVisible = true
        binding.previewVideo.isVisible = true
        binding.previewVideo.stopPlayback()
        binding.previewVideo.setVideoPath(path)
        binding.previewVideo.requestFocus()
        // scroll preview into view
        (binding.root.parent as? androidx.core.widget.NestedScrollView)?.smoothScrollTo(0, 0)
    }

    private fun playOutput() {
        val out = outputFile ?: return
        loadPreview(out.absolutePath, "Output preview")
        binding.previewVideo.start()
    }

    // ---------- Conversion ----------

    private fun onConvertClicked() {
        val input = inputFile?.absolutePath ?: run { toast("Pick a file first"); return }
        val isVideo = binding.tabLayout.selectedTabPosition == 0

        if (isVideo) {
            val vc = VideoCodec.fromDisplay(binding.videoCodecDropdown.text.toString())
            val useHw = binding.switchHardware.isChecked
            val hwAvailable = useHw && vc.hardwareEncoder != null
            if (vc.experimentalCrashRisk && !hwAvailable) {
                AlertDialog.Builder(this)
                    .setTitle("Experimental codec")
                    .setMessage("${vc.display} software encoding is known to crash the app on some devices " +
                        "(documented on MT6781 / POCO M4 Pro 4G). Enabling GPU acceleration uses the hardware " +
                        "encoder and may avoid the crash. Proceed with software encoding anyway?")
                    .setPositiveButton("Proceed") { _, _ -> doStartConversion(input, true) }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
        }
        doStartConversion(input, false)
    }

    private fun doStartConversion(input: String, @Suppress("UNUSED_PARAMETER") confirmedRisk: Boolean) {
        val isVideo = binding.tabLayout.selectedTabPosition == 0
        val myGen = ++conversionGen

        val outDir = File(getExternalFilesDir(null), "MediaConverter").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val args: Array<String>
        val ext: String
        if (isVideo) {
            val vc = VideoCodec.fromDisplay(binding.videoCodecDropdown.text.toString())
            // "Same as source" → probed source bitrate; fall back to codec default (0) if unknown
            val vbr = VideoBitrates.kbpsOf(binding.videoBitrateDropdown.text.toString())
                ?: sourceVideoBitrateKbps ?: 0
            val includeAudio = binding.switchIncludeAudio.isChecked
            val ac = VideoAudioCodec.fromDisplay(binding.videoAudioCodecDropdown.text.toString())
            val abr = AudioBitrates.kbpsOf(binding.videoAudioBitrateDropdown.text.toString())
                ?: sourceAudioBitrateKbps ?: 0
            val sr = SampleRates.valueOf(binding.videoSampleRateDropdown.text.toString())
            val ch = Channels.valueOf(binding.videoChannelsDropdown.text.toString())
            val norm = binding.switchNormalizeVideo.isChecked
            val useHw = binding.switchHardware.isChecked
            ext = vc.extension
            args = FFmpegEngine.buildVideoCommand(input, "OUTPUT", vc, vbr, includeAudio, ac, abr, sr, ch, norm, useHw)
        } else {
            val ac = AudioCodec.fromDisplay(binding.audioCodecDropdown.text.toString())
            val abr = AudioBitrates.kbpsOf(binding.audioBitrateDropdown.text.toString())
                ?: sourceAudioBitrateKbps ?: 0
            val sr = SampleRates.valueOf(binding.audioSampleRateDropdown.text.toString())
            val ch = Channels.valueOf(binding.audioChannelsDropdown.text.toString())
            val norm = binding.switchNormalizeAudio.isChecked
            ext = ac.extension
            args = FFmpegEngine.buildAudioCommand(input, "OUTPUT", ac, abr, sr, ch, norm)
        }

        val out = File(outDir, "converted_$stamp.$ext")
        outputFile = out
        val realArgs = args.map { if (it == "OUTPUT") out.absolutePath else it }.toTypedArray()

        setConverting(true)
        binding.outputSection.isVisible = false
        binding.progressBar.progress = 0
        binding.tvStatus.text = "Converting…"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val session = FFmpegEngine.execute(realArgs) { timeMs ->
                    if (durationMs > 0) {
                        val pct = (timeMs * 100 / durationMs).coerceIn(0, 100)
                        runOnUiThread {
                            binding.progressBar.progress = pct.toInt()
                            binding.tvStatus.text = "Converting… ${pct}%"
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    if (myGen != conversionGen) return@withContext // stale (cancelled & restarted)
                    setConverting(false)
                    val rc = session.getReturnCode()
                    when {
                        rc != null && ReturnCode.isSuccess(rc) -> {
                            binding.tvStatus.text = "Done. Saved to:\n${out.absolutePath}"
                            binding.outputSection.isVisible = true
                            toast("Conversion complete")
                            android.media.MediaScannerConnection.scanFile(
                                this@MainActivity, arrayOf(out.absolutePath), null, null
                            )
                        }
                        rc?.isValueCancel() == true -> {
                            binding.tvStatus.text = "Cancelled"
                        }
                        else -> {
                            val err = session.getFailStackTrace() ?: session.getAllLogsAsString() ?: "Unknown error"
                            binding.tvStatus.text = "Failed:\n${err.take(500)}"
                            toast("Conversion failed")
                        }
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    if (myGen != conversionGen) return@withContext
                    setConverting(false)
                    binding.tvStatus.text = "Error: ${e.message}"
                    toast("Error: ${e.message}")
                }
            }
        }
    }

    /**
     * Cancel the running FFmpeg session but do NOT cancel the coroutine — the
     * completion callback must still run to re-enable the UI. Cancelling the
     * coroutine alone previously left FFmpeg running and progress ticking.
     */
    private fun cancelConversion() {
        FFmpegKit.cancel()
        binding.tvStatus.text = "Cancelling…"
        binding.btnCancel.isEnabled = false
    }

    private fun setConverting(on: Boolean) {
        isConverting = on
        binding.btnConvert.isEnabled = !on && inputFile != null
        binding.btnCancel.isVisible = on
        binding.btnCancel.isEnabled = on
        binding.progressBar.isIndeterminate = on && durationMs == 0L
        setControlsEnabled(!on)
    }

    /** Lock every interactive control while a conversion is running (known crash cause). */
    private fun setControlsEnabled(enabled: Boolean) {
        binding.btnPickFile.isEnabled = enabled
        binding.switchIncludeAudio.isEnabled = enabled
        binding.switchHardware.isEnabled = enabled
        binding.switchNormalizeVideo.isEnabled = enabled
        binding.switchNormalizeAudio.isEnabled = enabled
        val dropdowns = listOf(
            binding.videoCodecDropdown, binding.videoBitrateDropdown,
            binding.videoAudioCodecDropdown, binding.videoAudioBitrateDropdown,
            binding.videoSampleRateDropdown, binding.videoChannelsDropdown,
            binding.audioCodecDropdown, binding.audioBitrateDropdown,
            binding.audioSampleRateDropdown, binding.audioChannelsDropdown
        )
        dropdowns.forEach { it.isEnabled = enabled }
    }

    // ---------- Output actions ----------

    private fun shareOutput() {
        val out = outputFile ?: return
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", out)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uri) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share converted file"))
    }

    private fun saveOutputTo() {
        val out = outputFile ?: return
        saveFile.launch(out.name)
    }

    private fun writeOutputTo(uri: Uri) {
        val out = outputFile ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            contentResolver.openOutputStream(uri)?.use { os ->
                out.inputStream().use { it.copyTo(os) }
            }
            withContext(Dispatchers.Main) { toast("Saved") }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    override fun onStop() {
        super.onStop()
        binding.previewVideo.stopPlayback()
    }
}
