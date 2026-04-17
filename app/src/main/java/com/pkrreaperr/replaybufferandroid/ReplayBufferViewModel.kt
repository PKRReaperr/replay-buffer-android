package com.pkrreaperr.replaybufferandroid

import android.app.Application
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ShutterMode(val label: String, val exposureTimeNs: Long?) {
    AUTO("Auto", null),
    ACTION("Action", 8_000_000L),
    CINEMATIC("Cinematic", 16_666_667L),
    NIGHT("Night", 33_333_333L)
}

data class ReplayBufferUiState(
    val permissionsGranted: Boolean = false,
    val replayDurationSeconds: Int = 30,
    val statusText: String = "Requesting camera permission...",
    val isRecording: Boolean = false,
    val isSaving: Boolean = false,
    val toastMessage: String? = null,
    val bufferedDurationMs: Long = 0L,
    val currentSegmentProgress: Float = 0f,
    val currentSegmentElapsedMs: Long = 0L,
    val zoomRatio: Float = 1f,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 4f,
    val exposureCompensation: Int = 0,
    val minExposureCompensation: Int = -4,
    val maxExposureCompensation: Int = 4,
    val torchEnabled: Boolean = false,
    val shutterMode: ShutterMode = ShutterMode.AUTO
) {
    val formattedReplayLength: String
        get() = formatDuration(replayDurationSeconds)

    val hasBufferedClip: Boolean
        get() = bufferedDurationMs > 0L

    val formattedBufferedDuration: String
        get() = formatDuration((bufferedDurationMs / 1000.0).roundToInt().coerceAtLeast(0))

    private val cappedBufferedDurationMs: Long
        get() = bufferedDurationMs.coerceAtMost(replayDurationSeconds * 1000L)

    val targetBufferedProgress: Float
        get() = if (replayDurationSeconds <= 0) 0f else {
            (cappedBufferedDurationMs.toFloat() / (replayDurationSeconds * 1000f)).coerceIn(0f, 1f)
        }

    val targetBufferedLabel: String
        get() = "${formatDuration((cappedBufferedDurationMs / 1000.0).roundToInt().coerceAtLeast(0))} / $formattedReplayLength"

    val currentSegmentLabel: String
        get() = "${(currentSegmentElapsedMs / 1000f).coerceAtLeast(0f).formatOneDecimal()} / 5.0s"
}

class ReplayBufferViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ReplayBufferUiState())
    val uiState: StateFlow<ReplayBufferUiState> = _uiState.asStateFlow()

    private val controller = ReplayBufferController(
        context = application.applicationContext,
        onStatus = { updateStatus(it) },
        onRecording = { updateRecording(it) },
        onSaving = { updateSaving(it) },
        onToast = { updateToast(it) },
        onBufferMetrics = { bufferedMs, currentSegmentProgress, currentSegmentElapsedMs ->
            _uiState.update {
                it.copy(
                    bufferedDurationMs = bufferedMs,
                    currentSegmentProgress = currentSegmentProgress,
                    currentSegmentElapsedMs = currentSegmentElapsedMs
                )
            }
        },
        onCameraReady = { minZoom, maxZoom, exposureRangeMin, exposureRangeMax ->
            _uiState.update {
                it.copy(
                    minZoomRatio = minZoom,
                    maxZoomRatio = maxZoom,
                    minExposureCompensation = exposureRangeMin,
                    maxExposureCompensation = exposureRangeMax,
                    zoomRatio = minZoom.coerceAtLeast(1f),
                    statusText = "Camera ready. Tap record to start buffering."
                )
            }
        }
    )

    fun onPermissionsResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                permissionsGranted = granted,
                statusText = if (granted) "Preparing camera..." else "Camera and microphone permissions are required."
            )
        }
    }

    fun attachPreview(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (!uiState.value.permissionsGranted) return

        viewModelScope.launch {
            try {
                controller.attachPreview(lifecycleOwner, previewView)
            } catch (error: Exception) {
                updateStatus("Unable to start camera preview.")
                updateRecording(false)
                updateToast(error.message ?: "Unable to start camera preview.")
            }
        }
    }

    fun toggleRecording() {
        if (_uiState.value.isRecording) {
            controller.stopBuffering()
        } else {
            controller.startBuffering()
        }
    }

    fun setReplayDuration(seconds: Int) {
        val clamped = seconds.coerceIn(10, 300)
        _uiState.update { it.copy(replayDurationSeconds = clamped) }
    }

    fun saveReplay() {
        controller.saveReplay(uiState.value.replayDurationSeconds)
    }

    fun consumeToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun formatDuration(seconds: Int): String = com.pkrreaperr.replaybufferandroid.formatDuration(seconds)

    fun setZoomRatio(zoomRatio: Float) {
        val clamped = zoomRatio.coerceIn(_uiState.value.minZoomRatio, _uiState.value.maxZoomRatio)
        _uiState.update { it.copy(zoomRatio = clamped) }
        controller.setZoomRatio(clamped)
    }

    fun setExposureCompensation(exposureCompensation: Int) {
        val clamped = exposureCompensation.coerceIn(
            _uiState.value.minExposureCompensation,
            _uiState.value.maxExposureCompensation
        )
        _uiState.update { it.copy(exposureCompensation = clamped) }
        controller.setExposureCompensation(clamped)
    }

    fun toggleTorch() {
        val enabled = !_uiState.value.torchEnabled
        _uiState.update { it.copy(torchEnabled = enabled) }
        controller.setTorch(enabled)
    }

    fun setShutterMode(shutterMode: ShutterMode) {
        _uiState.update { it.copy(shutterMode = shutterMode) }
        controller.setShutterMode(shutterMode)
    }

    private fun updateStatus(status: String) {
        _uiState.update { it.copy(statusText = status) }
    }

    private fun updateRecording(isRecording: Boolean) {
        _uiState.update {
            it.copy(
                isRecording = isRecording,
                currentSegmentProgress = if (isRecording) it.currentSegmentProgress else 0f,
                currentSegmentElapsedMs = if (isRecording) it.currentSegmentElapsedMs else 0L,
                statusText = if (isRecording) "Recording into replay buffer..." else "Camera ready. Tap record to start buffering."
            )
        }
    }

    private fun updateSaving(isSaving: Boolean) {
        _uiState.update { it.copy(isSaving = isSaving) }
    }

    private fun updateToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    override fun onCleared() {
        super.onCleared()
        controller.release()
    }
}

fun formatDuration(seconds: Int): String {
    return if (seconds >= 60) {
        val minutes = seconds / 60
        val remainder = seconds % 60
        if (remainder == 0) {
            "$minutes min"
        } else {
            "${minutes}m ${remainder}s"
        }
    } else {
        "${seconds}s"
    }
}

private fun Float.formatOneDecimal(): String = String.format("%.1f", this)
