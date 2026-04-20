package com.pkrreaperr.replaybufferandroid

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
class ReplayBufferController(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onRecording: (Boolean) -> Unit,
    private val onSaving: (Boolean) -> Unit,
    private val onToast: (String) -> Unit,
    private val onBufferMetrics: (Long, Float, Long) -> Unit,
    private val onCameraReady: (Float, Float, Int, Int) -> Unit
) {
    private data class RecordedSegment(
        val file: File,
        val durationMs: Long
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val segmentDirectory = File(context.cacheDir, "replay_segments").apply { mkdirs() }
    private val segmentDurationMs = 5_000L
    private val maxBufferedDurationMs = 300_000L

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null
    private var currentSegmentToken: Long = 0
    private var currentSegmentStartedAtMs: Long = 0L
    private var shouldBuffer = false
    private var pendingSaveDurationMs: Long? = null
    private var exportJob: Job? = null
    private var progressJob: Job? = null
    private val segments = LinkedList<RecordedSegment>()

    suspend fun attachPreview(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: awaitCameraProvider(context).also {
            cameraProvider = it
        }

        if (videoCapture != null) return

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.HD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .build()

        val capture = VideoCapture.withOutput(recorder)

        provider.unbindAll()
        val boundCamera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            capture
        )

        camera = boundCamera
        videoCapture = capture
        val zoomState = boundCamera.cameraInfo.zoomState.value
        val exposureState = boundCamera.cameraInfo.exposureState
        onCameraReady(
            zoomState?.minZoomRatio ?: 1f,
            zoomState?.maxZoomRatio ?: 4f,
            exposureState.exposureCompensationRange.lower,
            exposureState.exposureCompensationRange.upper
        )
    }

    fun startBuffering() {
        if (shouldBuffer || videoCapture == null) return
        shouldBuffer = true
        onRecording(true)
        onStatus("Recording into replay buffer...")
        scope.launch {
            startNextSegment()
        }
    }

    fun stopBuffering() {
        if (!shouldBuffer) return
        shouldBuffer = false
        onStatus("Stopping recording...")
        progressJob?.cancel()
        currentRecording?.stop() ?: onRecording(false)
    }

    fun saveReplay(replayDurationSeconds: Int) {
        val requestedDuration = replayDurationSeconds.coerceIn(10, 300) * 1_000L
        if (segments.isEmpty() && currentRecording == null) {
            onToast("The replay buffer has not captured enough footage yet.")
            return
        }

        pendingSaveDurationMs = requestedDuration
        onSaving(true)
        onStatus("Closing current segment...")
        if (currentRecording != null) {
            currentRecording?.stop()
        } else {
            pendingSaveDurationMs = null
            exportBufferedReplay(requestedDuration)
        }
    }

    fun release() {
        shouldBuffer = false
        progressJob?.cancel()
        currentRecording?.stop()
        currentRecording = null
        exportJob?.cancel()
        scope.launch(Dispatchers.IO) {
            segments.forEach { it.file.delete() }
            segmentDirectory.deleteRecursively()
        }
    }

    private suspend fun startNextSegment() {
        if (!shouldBuffer || currentRecording != null) return

        val capture = videoCapture ?: return
        val file = File(segmentDirectory, "segment-${System.currentTimeMillis()}.mp4")
        val fileOutputOptions = FileOutputOptions.Builder(file).build()

        val recording = prepareRecording(capture.output.prepareRecording(context, fileOutputOptions))
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        currentSegmentStartedAtMs = System.currentTimeMillis()
                        progressJob?.cancel()
                        progressJob = scope.launch {
                            while (shouldBuffer && currentRecording != null) {
                                val elapsedMs = (System.currentTimeMillis() - currentSegmentStartedAtMs)
                                    .coerceIn(0L, segmentDurationMs)
                                val totalBufferedMs = totalBufferedDurationMs() + elapsedMs
                                onBufferMetrics(
                                    totalBufferedMs.coerceAtMost(maxBufferedDurationMs),
                                    elapsedMs / segmentDurationMs.toFloat(),
                                    elapsedMs
                                )
                                delay(100)
                            }
                        }
                        onStatus("Recording into replay buffer...")
                        onRecording(true)
                    }

                    is VideoRecordEvent.Finalize -> {
                        handleSegmentFinalized(file, event)
                    }
                }
            }

        currentRecording = recording
        val token = ++currentSegmentToken

        scope.launch {
            delay(segmentDurationMs)
            if (currentSegmentToken == token) {
                currentRecording?.stop()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun prepareRecording(recording: PendingRecording): PendingRecording {
        return recording.withAudioEnabled()
    }

    private fun handleSegmentFinalized(file: File, event: VideoRecordEvent.Finalize) {
        currentRecording = null
        progressJob?.cancel()

        if (event.hasError()) {
            file.delete()
            shouldBuffer = false
            pendingSaveDurationMs = null
            onRecording(false)
            onSaving(false)
            onBufferMetrics(totalBufferedDurationMs(), 0f, 0L)
            onStatus("Recording interrupted. Tap record to resume.")
            onToast("Segment recording failed: ${event.error}")
            return
        }

        val durationMs = extractDurationMs(file).coerceAtLeast(1_000L)
        segments.add(RecordedSegment(file = file, durationMs = durationMs))
        trimBufferedSegments()
        onBufferMetrics(totalBufferedDurationMs(), 0f, 0L)

        val pendingSave = pendingSaveDurationMs
        if (pendingSave != null) {
            pendingSaveDurationMs = null
            exportBufferedReplay(pendingSave)
        }

        if (shouldBuffer) {
            scope.launch {
                startNextSegment()
            }
        } else {
            onStatus("Camera ready. Tap record to start buffering.")
            onRecording(false)
        }
    }

    private fun trimBufferedSegments() {
        var totalDuration = segments.sumOf { it.durationMs }
        while (totalDuration > maxBufferedDurationMs && segments.isNotEmpty()) {
            val removed = segments.removeFirst()
            totalDuration -= removed.durationMs
            removed.file.delete()
        }
    }

    private fun totalBufferedDurationMs(): Long = segments.sumOf { it.durationMs }

    private fun exportBufferedReplay(requestedDurationMs: Long) {
        val selectedSegments = selectSegments(requestedDurationMs)
        if (selectedSegments.isEmpty()) {
            onSaving(false)
            onToast("There is not enough buffered footage to export yet.")
            return
        }

        exportJob?.cancel()
        exportJob = scope.launch(Dispatchers.IO) {
            try {
                onStatus("Saving replay to Movies...")
                val mergedFile = File(context.cacheDir, "replay-${System.currentTimeMillis()}.mp4")
                exportSelectedSegments(selectedSegments, mergedFile)
                saveToMediaStore(mergedFile)
                mergedFile.delete()
                onStatus("Replay saved to Movies/ReplayBuffer.")
                onToast("Replay saved to Movies/ReplayBuffer.")
            } catch (error: Exception) {
                onStatus("Replay export failed.")
                onToast(error.message ?: "Replay export failed.")
            } finally {
                onSaving(false)
            }
        }
    }

    fun setZoomRatio(zoomRatio: Float) {
        val boundCamera = camera ?: return
        try {
            boundCamera.cameraControl.setZoomRatio(zoomRatio)
        } catch (error: Exception) {
            onToast(error.message ?: "This device rejected the zoom change.")
        }
    }

    fun setExposureCompensation(exposureCompensation: Int) {
        camera?.cameraControl?.setExposureCompensationIndex(exposureCompensation)
    }

    fun setTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    fun setShutterMode(shutterMode: ShutterMode) {
        val boundCamera = camera ?: return
        val camera2Control = Camera2CameraControl.from(boundCamera.cameraControl)
        try {
            val requestBuilder = CaptureRequestOptions.Builder()

            if (shutterMode.exposureTimeNs == null) {
                camera2Control.clearCaptureRequestOptions()
                onStatus("Auto shutter restored.")
            } else {
                requestBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON
                )
                requestBuilder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    android.util.Range(24, 30)
                )
                requestBuilder.setCaptureRequestOption(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    shutterMode.exposureTimeNs
                )
                camera2Control.setCaptureRequestOptions(requestBuilder.build())
                onStatus("${shutterMode.label} shutter selected.")
            }
        } catch (error: Exception) {
            onToast(error.message ?: "This device rejected the shutter setting.")
        }
    }

    private fun selectSegments(requestedDurationMs: Long): List<RecordedSegment> {
        val selected = mutableListOf<RecordedSegment>()
        var accumulated = 0L
        val iterator = segments.descendingIterator()

        while (iterator.hasNext()) {
            val segment = iterator.next()
            selected += segment
            accumulated += segment.durationMs
            if (accumulated >= requestedDurationMs) {
                break
            }
        }

        return selected.reversed()
    }

    private suspend fun exportSelectedSegments(selectedSegments: List<RecordedSegment>, outputFile: File) {
        val editedItems = selectedSegments.map { segment ->
            EditedMediaItem.Builder(MediaItem.fromUri(segment.file.toUri())).build()
        }
        val sequence = EditedMediaItemSequence(editedItems)
        val composition = Composition.Builder(listOf(sequence)).build()

        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val transformer = Transformer.Builder(context)
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                continuation.resume(Unit)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                continuation.resumeWithException(exportException)
                            }
                        }
                    )
                    .build()

                transformer.start(composition, outputFile.absolutePath)
            }
        }
    }

    private fun saveToMediaStore(file: File) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            saveToLegacyGallery(file)
            return
        }

        val resolver = context.contentResolver
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "replay-$timestamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ReplayBuffer")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, contentValues)
            ?: throw IllegalStateException("Unable to create output file in MediaStore.")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(file).use { input ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Unable to open MediaStore output stream.")

            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                },
                null,
                null
            )
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun saveToLegacyGallery(file: File) {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val moviesDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val replayDirectory = File(moviesDirectory, "ReplayBuffer")

        if (!replayDirectory.exists() && !replayDirectory.mkdirs()) {
            throw IllegalStateException("Unable to create Movies/ReplayBuffer.")
        }

        val outputFile = File(replayDirectory, "replay-$timestamp.mp4")
        FileInputStream(file).use { input ->
            outputFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        MediaScannerConnection.scanFile(
            context,
            arrayOf(outputFile.absolutePath),
            arrayOf("video/mp4"),
            null
        )
    }

    private fun extractDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: segmentDurationMs
        } finally {
            retriever.release()
        }
    }
}

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }
