package com.clipforge.ai

import android.content.ContentValues
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileInputStream

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var chooseButton: Button
    private lateinit var createButton: Button
    private lateinit var lengthSpinner: Spinner
    private lateinit var countSpinner: Spinner
    private lateinit var preview: PlayerView
    private lateinit var clipsList: LinearLayout
    private lateinit var aiHighlights: CheckBox
    private lateinit var apiKeyInput: EditText
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var clipTitles = mutableListOf<String>()
    private var clipReasons = mutableListOf<String>()
    private var player: ExoPlayer? = null
    private var selectedVideo: Uri? = null
    private var videoDurationMs = 0L
    private val pendingClips = ArrayDeque<Pair<Long, Long>>()
    private var completedCount = 0
    private var currentOutput: File? = null
    private var activeTransformer: Transformer? = null

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedVideo = uri
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { }
            videoDurationMs = readDuration(uri)
            if (videoDurationMs > 0) {
                playPreview(uri)
                status.text = "Video selected · " + (videoDurationMs / 1000) + " seconds\nPreview is loading. Press Play if playback is paused."
            } else {
                status.text = "Could not read this video's duration. Please choose another video."
            }
            createButton.isEnabled = videoDurationMs > 0
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun playPreview(uri: Uri) {
        if (player == null) {
            player = ExoPlayer.Builder(this).build().also { preview.player = it }
        }
        player?.apply {
            stop()
            clearMediaItems()
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
            setBackgroundColor(0xFF080D1C.toInt())
        }
        val scroll = ScrollView(this).apply { clipToPadding = false }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(22))
        }
        val logo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            contentDescription = "ClipForge AI logo"
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(76), dp(76)))
        val title = TextView(this).apply {
            text = "ClipForge AI"; textSize = 29f; typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setTextColor(0xFFF8FAFF.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val subtitle = TextView(this).apply {
            text = "Turn long videos into standout clips"; textSize = 14f
            setTextColor(0xFF9EACCA.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }
        header.addView(title, matchWrap()); header.addView(subtitle, matchWrap())
        page.addView(header, matchWrap())

        val previewCard = makeCard()
        previewCard.addView(sectionHeading("VIDEO PREVIEW"), matchWrap())
        preview = layoutInflater.inflate(R.layout.video_preview, page, false) as PlayerView
        previewCard.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        chooseButton = makeButton("＋  Choose video", 0xFF18B9A5.toInt(), 0xFF3978E8.toInt())
        chooseButton.setOnClickListener { videoPicker.launch(arrayOf("video/*")) }
        previewCard.addView(chooseButton, matchWrap())
        page.addView(previewCard, cardParams())

        val settingsCard = makeCard()
        settingsCard.addView(sectionHeading("CLIP SETTINGS"), matchWrap())
        settingsCard.addView(fieldLabel("Clip duration"), matchWrap())
        lengthSpinner = styledSpinner(listOf("1 minute", "1 minute 30 seconds", "2 minutes")).apply { setSelection(0) }
        settingsCard.addView(lengthSpinner, matchWrap())
        settingsCard.addView(fieldLabel("How many clips?  (1 to 10)"), matchWrap())
        countSpinner = styledSpinner((1..10).map { "$it clips" }).apply { setSelection(4) }
        settingsCard.addView(countSpinner, matchWrap())
        val countHelp = TextView(this).apply {
            text = "Choose the exact number you want. For example, selecting 5 creates up to 5 clips, not 10."
            textSize = 12f; setTextColor(0xFF9EACCA.toInt()); setPadding(0, dp(6), 0, dp(4))
        }
        settingsCard.addView(countHelp, matchWrap())
        page.addView(settingsCard, cardParams())

        val aiCard = makeCard()
        aiCard.addView(sectionHeading("AI HIGHLIGHT SETTINGS"), matchWrap())
        val aiSettingsHelp = TextView(this).apply {
            text = "Add your Groq API key. It is encrypted and saved securely on this phone."
            textSize = 12f; setTextColor(0xFF9EACCA.toInt())
        }
        aiCard.addView(aiSettingsHelp, matchWrap())
        apiKeyInput = EditText(this).apply {
            hint = if (GroqKeyStore.read(this@MainActivity).isNullOrBlank()) "Paste Groq API key" else "API key already saved"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(0xFFF8FAFF.toInt()); setHintTextColor(0xFF8290AC.toInt())
            background = roundedBackground(0xFF111C32.toInt(), 0xFF344768.toInt(), 12)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val saveKeyButton = makeButton("Save API key", 0xFF7358E8.toInt(), 0xFF3E65D9.toInt())
        saveKeyButton.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (!key.startsWith("gsk_")) status.text = "Please enter a valid Groq API key."
            else try {
                GroqKeyStore.save(this@MainActivity, key)
                apiKeyInput.text.clear(); apiKeyInput.hint = "API key already saved"
                status.text = "Groq API key saved securely on this phone."
            } catch (e: Exception) { status.text = "Could not save API key: " + (e.message ?: "storage error") }
        }
        aiCard.addView(apiKeyInput, matchWrap())
        aiCard.addView(saveKeyButton, matchWrap())
        aiHighlights = CheckBox(this).apply {
            text = "AI Auto Select Highlights + Titles"; textSize = 14f
            setTextColor(0xFFF8FAFF.toInt())
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFF9A7BFF.toInt())
        }
        aiCard.addView(aiHighlights, matchWrap())
        val aiHelp = TextView(this).apply {
            text = "AI selects moments from spoken audio and creates a title for each clip. Internet and Groq API usage are required."
            textSize = 12f; setTextColor(0xFF9EACCA.toInt())
        }
        aiCard.addView(aiHelp, matchWrap())
        page.addView(aiCard, cardParams())

        createButton = makeButton("✦  Create my clips", 0xFFFF9A44.toInt(), 0xFFEC557E.toInt()).apply {
            isEnabled = false
            setOnClickListener { createClips() }
        }
        page.addView(createButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply {
            topMargin = dp(5); bottomMargin = dp(12)
        })

        val progressCard = makeCard()
        status = TextView(this).apply {
            text = "Choose a video from your phone to begin."
            textSize = 14f; setTextColor(0xFFE0E8FA.toInt())
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }
        progressCard.addView(sectionHeading("STATUS"), matchWrap())
        progressCard.addView(status, matchWrap())
        page.addView(progressCard, cardParams())

        val clipsCard = makeCard()
        clipsCard.addView(sectionHeading("YOUR CREATED CLIPS"), matchWrap())
        clipsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clipsCard.addView(clipsList, matchWrap())
        page.addView(clipsCard, cardParams())

        scroll.addView(page)
        setContentView(scroll)
    }

    private fun makeCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = roundedBackground(0xFF17243D.toInt(), 0xFF344C73.toInt(), 20)
        elevation = dp(8).toFloat()
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(17) }

    private fun sectionHeading(value: String) = TextView(this).apply {
        text = value; textSize = 13f; letterSpacing = 0.12f
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        setTextColor(0xFF7FE4E0.toInt()); setPadding(0, 0, 0, dp(12))
    }

    private fun fieldLabel(value: String) = TextView(this).apply {
        text = value; textSize = 14f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(0xFFF3F6FF.toInt()); setPadding(0, dp(10), 0, dp(5))
    }

    private fun makeButton(label: String, top: Int, bottom: Int) = Button(this).apply {
        text = label; textSize = 15f; isAllCaps = false
        typeface = Typeface.create("sans-serif-bold", Typeface.NORMAL)
        setTextColor(0xFFFFFFFF.toInt())
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), 0x66FFFFFF)
        }
        elevation = dp(7).toFloat()
        stateListAnimator = null
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun roundedBackground(color: Int, stroke: Int, radius: Int) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(color, color)).apply {
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun styledSpinner(items: List<String>) = Spinner(this).apply {
        adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                return super.getView(position, convertView, parent).apply {
                    (this as? TextView)?.apply {
                        setTextColor(0xFFF4F7FF.toInt()); textSize = 15f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setPadding(dp(12), dp(8), dp(8), dp(8))
                    }
                }
            }
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                return super.getDropDownView(position, convertView, parent).apply {
                    (this as? TextView)?.apply { setTextColor(0xFF17243D.toInt()); textSize = 15f; setPadding(dp(14), dp(12), dp(12), dp(12)) }
                }
            }
        }
        background = roundedBackground(0xFF111C32.toInt(), 0xFF526B92.toInt(), 12)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun readDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L }
        finally { retriever.release() }
    }

    private fun createClips() {
        val uri = selectedVideo ?: return
        val seconds = listOf(60, 90, 120)[lengthSpinner.selectedItemPosition]
        val requestedCount = countSpinner.selectedItemPosition + 1
        val clipMs = seconds * 1000L
        if (videoDurationMs < clipMs) {
            status.text = "This video is shorter than " + seconds + " seconds. Choose a shorter clip length."
            return
        }
        if (aiHighlights.isChecked) {
            val key = GroqKeyStore.read(this)
            if (key.isNullOrBlank()) {
                status.text = "Save your Groq API key before using AI highlights."
                return
            }
            startClipWorkService()
            createButton.isEnabled = false
            chooseButton.isEnabled = false
            status.text = "AI is analysing the video's speech and selecting highlights. Keep the app open."
            worker.execute {
                try {
                    val selected = GroqHighlightAnalyzer.analyse(this, uri, videoDurationMs, clipMs, requestedCount, key)
                    runOnUiThread {
                        pendingClips.clear()
                        clipTitles.clear()
                        clipReasons.clear()
                        selected.forEach { clip ->
                            pendingClips.add(clip.startMs to clip.endMs)
                            clipTitles.add(clip.title)
                            clipReasons.add(clip.reason)
                        }
                        completedCount = 0
                        clipsList.removeAllViews()
                        status.text = "AI selected " + selected.size + " highlights. Creating clips..."
                        exportNext(uri)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        status.text = "AI analysis failed: " + (e.message ?: "Check internet and API key.")
                        createButton.isEnabled = true
                        chooseButton.isEnabled = true
                    }
                }
            }
            return
        }
        val availableCount = (videoDurationMs / clipMs).toInt()
        val count = minOf(requestedCount, availableCount)
        if (count < 1) {
            status.text = "This video is shorter than " + seconds + " seconds. Choose a shorter clip length."
            return
        }
        startClipWorkService()
        pendingClips.clear()
        clipTitles.clear()
        clipReasons.clear()
        repeat(count) { index ->
            val start = index * clipMs
            pendingClips.add(start to (start + clipMs))
            clipTitles.add("Clip " + (index + 1))
            clipReasons.add("")
        }
        completedCount = 0
        clipsList.removeAllViews()
        createButton.isEnabled = false
        chooseButton.isEnabled = false
        status.text = "Preparing " + count + " of your requested " + requestedCount + " clips on this phone. Keep ClipForge open while exporting."
        exportNext(uri)
    }

    private fun exportNext(uri: Uri) {
        if (pendingClips.isEmpty()) {
            status.text = "Finished! " + completedCount + " clips saved in Movies/ClipForge AI."
            stopClipWorkService()
            createButton.isEnabled = true
            chooseButton.isEnabled = true
            return
        }
        val (startMs, endMs) = pendingClips.removeFirst()
        val clipNumber = completedCount + 1
        val output = File(cacheDir, "clipforge_" + System.currentTimeMillis() + "_" + clipNumber + ".mp4")
        currentOutput = output
        val mediaItem = MediaItem.Builder().setUri(uri).setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs).setEndPositionMs(endMs).build()
        ).build()
        val editedItem = EditedMediaItem.Builder(mediaItem).build()
        val listener = object : Transformer.Listener {
            override fun onCompleted(
                composition: androidx.media3.transformer.Composition,
                exportResult: ExportResult
            ) {
                try {
                    val savedUri = saveToGallery(output, "ClipForge_" + clipNumber + "_" + (startMs / 1000) + "s.mp4")
                    completedCount++
                    addClipRow(clipNumber, startMs, endMs, savedUri,
                        clipTitles.getOrNull(clipNumber - 1) ?: ("Clip " + clipNumber),
                        clipReasons.getOrNull(clipNumber - 1).orEmpty())
                    output.delete()
                    status.text = "Exported " + completedCount + " clips..."
                    exportNext(uri)
                } catch (error: Exception) {
                    fail("Could not save the exported clip: " + error.message)
                }
            }

            override fun onError(
                composition: androidx.media3.transformer.Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                fail("Export stopped: " + (exportException.message ?: "Unsupported video format"))
            }
        }
        try {
            activeTransformer = Transformer.Builder(this).addListener(listener).build()
            activeTransformer?.start(editedItem, output.absolutePath)
        } catch (error: Exception) {
            fail("Could not start export: " + error.message)
        }
    }

    private fun addClipRow(number: Int, startMs: Long, endMs: Long, uri: Uri, title: String, reason: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val info = TextView(this).apply {
            text = title + "\n" + (startMs / 1000) + "s to " + (endMs / 1000) + "s"
            textSize = 14f
            setTextColor(0xFFD5DDF0.toInt())
        }
        val play = Button(this).apply {
            text = "Preview"
            setOnClickListener {
                playPreview(uri)
                status.text = "Playing clip " + number
            }
        }
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (reason.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = reason
                textSize = 12f
                setTextColor(0xFF9DAAC2.toInt())
            }, matchWrap())
        }
        clipsList.addView(row, matchWrap())
    }

    private fun saveToGallery(source: File, displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ClipForge AI")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val destination = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Gallery could not create a video entry")
        try {
            contentResolver.openOutputStream(destination)?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            } ?: error("Gallery output stream unavailable")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(destination, values, null, null)
            return destination
        } catch (error: Exception) {
            contentResolver.delete(destination, null, null)
            throw error
        }
    }

    private fun fail(message: String) {
        stopClipWorkService()
        status.text = message
        pendingClips.clear()
        currentOutput?.delete()
        createButton.isEnabled = true
        chooseButton.isEnabled = true
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        preview.player = null
        player?.release()
        player = null
        // Keep background analysis from being cancelled when the Activity is temporarily stopped.
        super.onDestroy()
    }

    private fun startClipWorkService() {
        val intent = Intent(this, ClipWorkService::class.java).apply { action = ClipWorkService.ACTION_START }
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun stopClipWorkService() {
        stopService(Intent(this, ClipWorkService::class.java))
    }
}
