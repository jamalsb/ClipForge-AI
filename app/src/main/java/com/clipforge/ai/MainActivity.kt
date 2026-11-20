package com.clipforge.ai

import android.content.ContentValues
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
    private lateinit var preview: PlayerView
    private lateinit var clipsList: LinearLayout
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
            setPadding(24, 28, 24, 24)
            setBackgroundColor(0xFF0B1020.toInt())
        }
        val scroll = ScrollView(this)
        val title = TextView(this).apply {
            text = "ClipForge AI"; textSize = 30f
            setTextColor(0xFFF4F6FF.toInt()); gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Long videos in. Short clips out."; textSize = 15f
            setTextColor(0xFF9DAAC2.toInt()); gravity = Gravity.CENTER
            setPadding(0, 8, 0, 20)
        }
        val previewLabel = TextView(this).apply {
            text = "Video preview"; textSize = 17f
            setTextColor(0xFFF4F6FF.toInt()); setPadding(0, 8, 0, 8)
        }
        preview = PlayerView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(230)
            )
        }

        chooseButton = Button(this).apply {
            text = "Choose video"
            setOnClickListener { videoPicker.launch(arrayOf("video/*")) }
        }
        val lengthLabel = TextView(this).apply {
            text = "Clip length"; textSize = 16f
            setTextColor(0xFFF4F6FF.toInt()); setPadding(0, 20, 0, 8)
        }
        lengthSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("15 seconds", "30 seconds", "45 seconds", "60 seconds"))
            setSelection(1)
        }
        createButton = Button(this).apply {
            text = "Create clips"; isEnabled = false
            setOnClickListener { createClips() }
        }
        status = TextView(this).apply {
            text = "Choose a video from your phone to begin."
            textSize = 14f; setTextColor(0xFFD5DDF0.toInt())
            setPadding(0, 18, 0, 16)
        }
        val clipsTitle = TextView(this).apply {
            text = "Created clips"; textSize = 19f
            setTextColor(0xFFF4F6FF.toInt()); setPadding(0, 16, 0, 8)
        }
        clipsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(title, matchWrap())
        page.addView(subtitle, matchWrap())
        page.addView(previewLabel, matchWrap())
        page.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)))
        page.addView(chooseButton, matchWrap())
        page.addView(lengthLabel, matchWrap())
        page.addView(lengthSpinner, matchWrap())
        page.addView(createButton, matchWrap())
        page.addView(status, matchWrap())
        page.addView(clipsTitle, matchWrap())
        page.addView(clipsList, matchWrap())
        scroll.addView(page)
        setContentView(scroll)
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
        val seconds = listOf(15, 30, 45, 60)[lengthSpinner.selectedItemPosition]
        val clipMs = seconds * 1000L
        val count = (videoDurationMs / clipMs).toInt()
        if (count < 1) {
            status.text = "This video is shorter than " + seconds + " seconds. Choose a shorter clip length."
            return
        }
        pendingClips.clear()
        repeat(count) { index ->
            val start = index * clipMs
            pendingClips.add(start to (start + clipMs))
        }
        completedCount = 0
        clipsList.removeAllViews()
        createButton.isEnabled = false
        chooseButton.isEnabled = false
        status.text = "Preparing " + count + " clips on this phone. Keep ClipForge open while exporting."
        exportNext(uri)
    }

    private fun exportNext(uri: Uri) {
        if (pendingClips.isEmpty()) {
            status.text = "Finished! " + completedCount + " clips saved in Movies/ClipForge AI."
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
                    addClipRow(clipNumber, startMs, endMs, savedUri)
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

    private fun addClipRow(number: Int, startMs: Long, endMs: Long, uri: Uri) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val info = TextView(this).apply {
            text = "Clip " + number + " · " + (startMs / 1000) + "s to " + (endMs / 1000) + "s"
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
        super.onDestroy()
    }
}
