package com.pkrreaperr.replaybufferandroid

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

private enum class OverlayPanel {
    Replay,
    Adjust,
    Light
}

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<ReplayBufferViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    ReplayBufferScreen(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun ReplayBufferScreen(viewModel: ReplayBufferViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsState()

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var activePanel by rememberSaveable { mutableStateOf<OverlayPanel?>(null) }
    var interactionTick by remember { mutableIntStateOf(0) }
    val overlayAlpha by animateFloatAsState(
        targetValue = if (controlsVisible || !state.isRecording) 1f else 0.12f,
        label = "overlayAlpha"
    )
    val previewTapSource = remember { MutableInteractionSource() }
    val missingPermissionMessage = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        "Camera, microphone, and storage permissions are required."
    } else {
        "Camera and microphone permissions are required."
    }

    fun noteInteraction() {
        controlsVisible = true
        interactionTick++
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.onPermissionsResult(result.values.all { it })
    }

    LaunchedEffect(Unit) {
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.consumeToast()
        }
    }

    LaunchedEffect(state.isRecording, interactionTick) {
        if (state.isRecording) {
            delay(2500)
            controlsVisible = false
        } else {
            controlsVisible = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (state.permissionsGranted) {
            AndroidView(
                factory = { previewContext ->
                    PreviewView(previewContext).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        viewModel.attachPreview(lifecycleOwner, this)
                    }
                },
                update = { previewView ->
                    viewModel.attachPreview(lifecycleOwner, previewView)
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF111111)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = missingPermissionMessage,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = previewTapSource,
                    indication = null
                ) {
                    controlsVisible = !controlsVisible
                    interactionTick++
                }
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.18f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.36f)
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            TopOverlay(
                state = state,
                activePanel = activePanel,
                alpha = overlayAlpha,
                onChevronTap = {
                    noteInteraction()
                    controlsVisible = !controlsVisible
                },
                onPanelTap = { panel ->
                    noteInteraction()
                    activePanel = if (activePanel == panel) null else panel
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            )

            if (activePanel != null && (controlsVisible || !state.isRecording)) {
                FloatingPanel(
                    panel = activePanel!!,
                    state = state,
                    alpha = overlayAlpha,
                    onReplayDurationChange = {
                        noteInteraction()
                        viewModel.setReplayDuration(it)
                    },
                    onSaveReplay = {
                        noteInteraction()
                        viewModel.saveReplay()
                    },
                    onZoomChange = {
                        noteInteraction()
                        viewModel.setZoomRatio(it)
                    },
                    onExposureChange = {
                        noteInteraction()
                        viewModel.setExposureCompensation(it)
                    },
                    onShutterModeSelected = {
                        noteInteraction()
                        viewModel.setShutterMode(it)
                    },
                    onTorchToggle = {
                        noteInteraction()
                        viewModel.toggleTorch()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 168.dp)
                        .fillMaxWidth(0.92f)
                )
            }

            BottomOverlay(
                state = state,
                alpha = overlayAlpha,
                submenuOpen = activePanel != null,
                onSaveReplay = {
                    noteInteraction()
                    viewModel.saveReplay()
                },
                onToggleRecording = {
                    noteInteraction()
                    viewModel.toggleRecording()
                },
                onAdjustTap = {
                    noteInteraction()
                    activePanel = if (activePanel == OverlayPanel.Adjust) null else OverlayPanel.Adjust
                },
                onZoomPresetTap = {
                    noteInteraction()
                    viewModel.setZoomRatio(it)
                },
                adjustSelected = activePanel == OverlayPanel.Adjust,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TopOverlay(
    state: ReplayBufferUiState,
    activePanel: OverlayPanel?,
    alpha: Float,
    onChevronTap: () -> Unit,
    onPanelTap: (OverlayPanel) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.graphicsLayer(alpha = alpha)
    ) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier.weight(1f)
        ) {
            SmallCircleButton(
                selected = activePanel == OverlayPanel.Replay,
                onClick = { onPanelTap(OverlayPanel.Replay) }
            ) {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = "Replay settings",
                    tint = Color.White
                )
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.weight(1f)
        ) {
            SmallCircleButton(selected = false, onClick = onChevronTap) {
                Icon(
                    imageVector = Icons.Rounded.ExpandLess,
                    contentDescription = "Show or hide controls",
                    tint = Color.White
                )
            }
        }

        Box(
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier.weight(1f)
        ) {
            SmallCircleButton(
                selected = activePanel == OverlayPanel.Light,
                onClick = { onPanelTap(OverlayPanel.Light) }
            ) {
                Icon(
                    imageVector = if (state.torchEnabled) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                    contentDescription = "Light controls",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun FloatingPanel(
    panel: OverlayPanel,
    state: ReplayBufferUiState,
    alpha: Float,
    onReplayDurationChange: (Int) -> Unit,
    onSaveReplay: () -> Unit,
    onZoomChange: (Float) -> Unit,
    onExposureChange: (Int) -> Unit,
    onShutterModeSelected: (ShutterMode) -> Unit,
    onTorchToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .graphicsLayer(alpha = alpha)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.38f))
            .padding(16.dp)
    ) {
        when (panel) {
            OverlayPanel.Replay -> {
                Text("Replay", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = state.targetBufferedLabel,
                    color = Color.White.copy(alpha = 0.86f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = state.replayDurationSeconds.toFloat(),
                    onValueChange = { onReplayDurationChange(it.toInt()) },
                    valueRange = 10f..300f
                )
                LinearProgressIndicator(
                    progress = { state.targetBufferedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = Color(0xFFFFD54F),
                    trackColor = Color.White.copy(alpha = 0.16f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(30, 60, 120).forEach { preset ->
                        CameraChip(
                            label = formatDuration(preset),
                            selected = state.replayDurationSeconds == preset,
                            onClick = { onReplayDurationChange(preset) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Button(
                    onClick = onSaveReplay,
                    enabled = state.hasBufferedClip && !state.isSaving,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.18f),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Replay")
                }
            }

            OverlayPanel.Adjust -> {
                Text("Adjust", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text("Zoom ${String.format("%.1f", state.zoomRatio)}x", color = Color.White)
                Slider(
                    value = state.zoomRatio,
                    onValueChange = onZoomChange,
                    valueRange = state.minZoomRatio..state.maxZoomRatio
                )
                Text("Exposure ${state.exposureCompensation}", color = Color.White)
                Slider(
                    value = state.exposureCompensation.toFloat(),
                    onValueChange = { onExposureChange(it.toInt()) },
                    valueRange = state.minExposureCompensation.toFloat()..state.maxExposureCompensation.toFloat()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ShutterMode.entries.forEach { mode ->
                        CameraChip(
                            label = mode.label,
                            selected = state.shutterMode == mode,
                            onClick = { onShutterModeSelected(mode) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            OverlayPanel.Light -> {
                Text("Light", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = if (state.torchEnabled) "Torch enabled" else "Torch disabled",
                    color = Color.White.copy(alpha = 0.84f)
                )
                Button(
                    onClick = onTorchToggle,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.18f),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.torchEnabled) "Turn Torch Off" else "Turn Torch On")
                }
            }
        }
    }
}

@Composable
private fun BottomOverlay(
    state: ReplayBufferUiState,
    alpha: Float,
    submenuOpen: Boolean,
    onSaveReplay: () -> Unit,
    onToggleRecording: () -> Unit,
    onAdjustTap: () -> Unit,
    onZoomPresetTap: (Float) -> Unit,
    adjustSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val zoomPresets = listOf(0.5f, 1f, 2f)
        .map { it.coerceIn(state.minZoomRatio, state.maxZoomRatio) }
        .distinct()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .graphicsLayer(alpha = alpha)
            .padding(bottom = 8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (submenuOpen) 6.dp else 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "REPLAY BUFFER",
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
            Text(
                text = state.statusText,
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            if (submenuOpen) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.18f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = state.targetBufferedLabel,
                        color = Color.White.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.74f)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.22f))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        LinearProgressIndicator(
                            progress = { state.targetBufferedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = if (state.isRecording) Color(0xFFFF4D4D) else Color.White,
                            trackColor = Color.White.copy(alpha = 0.16f)
                        )
                        Text(
                            text = state.targetBufferedLabel,
                            color = Color.White.copy(alpha = 0.92f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        if (zoomPresets.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.25f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                zoomPresets.forEach { zoom ->
                    val label = if (zoom % 1f == 0f) "${zoom.toInt()}x" else "${zoom}x"
                    val selected = kotlin.math.abs(state.zoomRatio - zoom) < 0.15f
                    Text(
                        text = label,
                        color = if (selected) Color(0xFFFFD54F) else Color.White,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { onZoomPresetTap(zoom) }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.28f))
                        .clickable(enabled = state.hasBufferedClip && !state.isSaving, onClick = onSaveReplay)
                ) {
                    Text(
                        text = "SAVE",
                        color = if (state.hasBufferedClip) Color.White else Color.White.copy(alpha = 0.35f),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(92.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .clickable(enabled = state.permissionsGranted, onClick = onToggleRecording)
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (state.isRecording) 32.dp else 68.dp)
                            .clip(if (state.isRecording) RoundedCornerShape(10.dp) else CircleShape)
                            .background(if (state.isRecording) Color(0xFFFF4D4D) else Color.White)
                    )
                }
            }

            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier.weight(1f)
            ) {
                SmallCircleButton(selected = adjustSelected, onClick = onAdjustTap) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = "Adjustment controls",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallCircleButton(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (selected) Color.White.copy(alpha = 0.24f) else Color.Black.copy(alpha = 0.24f)
            )
    ) {
        content()
    }
}

@Composable
private fun CameraChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f),
            contentColor = if (selected) Color(0xFFFFD54F) else Color.White
        ),
        modifier = modifier
    ) {
        Text(text = label)
    }
}
