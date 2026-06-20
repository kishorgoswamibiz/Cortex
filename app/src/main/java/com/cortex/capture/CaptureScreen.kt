package com.cortex.capture

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cortex.data.AppDatabase
import com.cortex.data.CaptureEntity
import com.cortex.ui.theme.InkMist
import com.cortex.ui.theme.glassSurface
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Which conversation the current voice session belongs to. */
private enum class VoiceMode { CAPTURE, ASK }

class CaptureStatusViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.getDatabase(getApplication()).cortexDao()
    val latest: StateFlow<CaptureEntity?> = dao.getLatestCaptureFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val pendingCount: StateFlow<Int> = dao.getPendingCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CaptureStatusViewModel(app) as T
    }
}

@Composable
fun CaptureScreen(
    speechManager: SpeechManager,
    onProcessCapture: (String) -> Unit,
    onAskFromHome: (String) -> Unit
) {
    val context = LocalContext.current
    val speechState by speechManager.speechState.collectAsState()
    val statusVm: CaptureStatusViewModel =
        viewModel(factory = CaptureStatusViewModel.Factory(context.applicationContext as Application))
    val latest by statusVm.latest.collectAsState()
    val pending by statusVm.pendingCount.collectAsState()

    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { hasRecordPermission = it }
    )

    var editableText by remember { mutableStateOf("") }
    var voiceMode by remember { mutableStateOf(VoiceMode.CAPTURE) }
    var justProcessed by remember { mutableStateOf(false) }

    // When the recogniser (or the "type instead" hook) hands us a final
    // transcript, drop straight into the editable Review for whichever lane
    // the user started in — Capture or Ask. We never auto-submit; the user
    // always confirms with a tap on Process / Ask.
    LaunchedEffect(speechState) {
        val s = speechState
        if (s is SpeechState.Finished) editableText = s.text
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Header: wordmark + status pill, always visible.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Cortex",
                style = MaterialTheme.typography.headlineMedium,
                color = InkMist.PrimaryText
            )
            Text(
                "a quiet second brain",
                style = MaterialTheme.typography.labelSmall,
                color = InkMist.SecondaryText
            )
            Spacer(Modifier.height(12.dp))
            CaptureStatusPill(latest, pending, justProcessed)
        }

        // Active state — a plain `when` (no AnimatedContent) so the orb's
        // InfiniteTransition isn't re-created on every RMS update.
        // (Amendment T-A2: glitch fix.)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val state = speechState) {
                is SpeechState.Idle, is SpeechState.Error -> IdleState(
                    errorMessage = (state as? SpeechState.Error)?.message,
                    onTapCapture = {
                        voiceMode = VoiceMode.CAPTURE
                        startListeningWithPermission(
                            hasPermission = hasRecordPermission,
                            request = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            start = { speechManager.startListening() }
                        )
                    },
                    onTapAsk = {
                        voiceMode = VoiceMode.ASK
                        startListeningWithPermission(
                            hasPermission = hasRecordPermission,
                            request = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            start = { speechManager.startListening() }
                        )
                    },
                    onTypeCapture = {
                        voiceMode = VoiceMode.CAPTURE
                        editableText = ""
                        speechManager.enterReview("")
                    },
                    onTypeAsk = {
                        voiceMode = VoiceMode.ASK
                        editableText = ""
                        speechManager.enterReview("")
                    }
                )
                is SpeechState.Starting -> PreparingState(
                    mode = voiceMode,
                    label = "Listening for you…",
                    onCancel = { speechManager.reset() }
                )
                is SpeechState.Listening -> ListeningState(
                    partialText = state.partialText,
                    rms = state.rms,
                    mode = voiceMode,
                    onStop = { speechManager.stopListening() }
                )
                is SpeechState.Stopping -> PreparingState(
                    mode = voiceMode,
                    label = "Tidying that up…",
                    onCancel = null
                )
                is SpeechState.Finished -> ReviewState(
                    mode = voiceMode,
                    text = editableText,
                    onTextChange = { editableText = it },
                    onReRecord = {
                        editableText = ""
                        speechManager.startListening()
                    },
                    onDiscard = {
                        editableText = ""
                        voiceMode = VoiceMode.CAPTURE
                        speechManager.reset()
                    },
                    onSubmit = {
                        val toSend = editableText.trim()
                        if (toSend.isNotEmpty()) {
                            when (voiceMode) {
                                VoiceMode.CAPTURE -> {
                                    onProcessCapture(toSend)
                                    justProcessed = true
                                }
                                VoiceMode.ASK -> onAskFromHome(toSend)
                            }
                            editableText = ""
                            voiceMode = VoiceMode.CAPTURE
                            speechManager.reset()
                        }
                    }
                )
            }
        }
    }

    LaunchedEffect(latest?.id, latest?.status) {
        if (justProcessed && latest?.status != "pending") justProcessed = false
    }
}

private fun startListeningWithPermission(
    hasPermission: Boolean,
    request: () -> Unit,
    start: () -> Unit
) {
    if (hasPermission) start() else request()
}

@Composable
private fun IdleState(
    errorMessage: String?,
    onTapCapture: () -> Unit,
    onTapAsk: () -> Unit,
    onTypeCapture: () -> Unit,
    onTypeAsk: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        BreathingOrb(onClick = onTapCapture, scale = 1f, mode = VoiceMode.CAPTURE, energetic = false)
        Spacer(Modifier.height(24.dp))
        Text(
            "Tap to capture",
            style = MaterialTheme.typography.displayMedium,
            color = InkMist.PrimaryText,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            errorMessage ?: "Speak a thought; Cortex will file it.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (errorMessage != null) InkMist.DomainPersonal else InkMist.SecondaryText,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        TextLink("or type a thought", onClick = onTypeCapture)
        Spacer(Modifier.height(20.dp))
        AskAnythingRow(onTapAsk = onTapAsk, onTypeAsk = onTypeAsk)
        Spacer(Modifier.height(24.dp))
        SuggestionsCard()
    }
}

@Composable
private fun TextLink(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(100))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            Icons.Outlined.Edit,
            contentDescription = null,
            tint = InkMist.SecondaryText,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = InkMist.SecondaryText, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun AskAnythingRow(onTapAsk: () -> Unit, onTypeAsk: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(100))
            .background(InkMist.MoonstoneSoft.copy(alpha = 0.14f))
            .border(1.dp, InkMist.MoonstoneSoft.copy(alpha = 0.45f), RoundedCornerShape(100))
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = InkMist.Moonstone,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Ask anything",
            color = InkMist.Moonstone,
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(Modifier.width(12.dp))
        // Type-a-question well
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(InkMist.Moonstone.copy(alpha = 0.12f))
                .clickable { onTypeAsk() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = "Ask by text",
                tint = InkMist.Moonstone,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        // Voice well
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(InkMist.Moonstone)
                .clickable { onTapAsk() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Mic,
                contentDescription = "Ask by voice",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun PreparingState(mode: VoiceMode, label: String, onCancel: (() -> Unit)?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        BreathingLoader(mode = mode)
        Spacer(Modifier.height(28.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleLarge,
            color = if (mode == VoiceMode.ASK) InkMist.MoonstoneSoft else InkMist.Moonstone
        )
        if (onCancel != null) {
            Spacer(Modifier.height(12.dp))
            TextLink("Cancel", onClick = onCancel)
        }
    }
}

@Composable
private fun BreathingLoader(mode: VoiceMode) {
    val accent = if (mode == VoiceMode.ASK) InkMist.MoonstoneSoft else InkMist.Moonstone
    val transition = rememberInfiniteTransition(label = "loader")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(1200, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "spin"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    Box(
        modifier = Modifier.size(220.dp),
        contentAlignment = Alignment.Center
    ) {
        // Faint backing ring
        Canvas(modifier = Modifier.size(140.dp)) {
            drawArc(
                color = accent.copy(alpha = 0.18f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        // Bright sweep arc, rotated continuously
        Canvas(
            modifier = Modifier
                .size(140.dp)
                .rotate(rotation)
        ) {
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(
                        Color.Transparent,
                        accent.copy(alpha = 0.05f),
                        accent.copy(alpha = 0.95f)
                    )
                ),
                startAngle = 0f,
                sweepAngle = 280f,
                useCenter = false,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        // Quiet sapphire dot at the centre, gently pulsing
        Box(
            modifier = Modifier
                .size((18 * pulse).dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = pulse))
        )
    }
}

@Composable
private fun ListeningState(
    partialText: String,
    rms: Float,
    mode: VoiceMode,
    onStop: () -> Unit
) {
    val rmsScale = 1f + (rms / 20f).coerceIn(0f, 0.30f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        BreathingOrb(onClick = onStop, scale = rmsScale, mode = mode, energetic = true)
        Spacer(Modifier.height(28.dp))
        Text(
            if (mode == VoiceMode.ASK) "Listening — your question" else "Listening",
            style = MaterialTheme.typography.titleLarge,
            color = if (mode == VoiceMode.ASK) InkMist.MoonstoneSoft else InkMist.Moonstone
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .glassSurface(cornerRadius = 18)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Text(
                text = partialText.ifEmpty { "…" },
                color = InkMist.PrimaryText,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Start
            )
        }
    }
}

@Composable
private fun ReviewState(
    mode: VoiceMode,
    text: String,
    onTextChange: (String) -> Unit,
    onReRecord: () -> Unit,
    onDiscard: () -> Unit,
    onSubmit: () -> Unit
) {
    val isAsk = mode == VoiceMode.ASK
    val title = if (isAsk) "Your question" else "Review"
    val helper = if (isAsk)
        "Type your question, or edit the transcript before asking Cortex."
    else
        "Type a thought, or edit anything we misheard before filing it."
    val submitLabel = if (isAsk) "Ask" else "Process"
    val placeholder = if (isAsk) "What would you like to know?" else "What would you like to remember?"

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = InkMist.Moonstone
        )
        Spacer(Modifier.height(4.dp))
        Text(
            helper,
            style = MaterialTheme.typography.bodyMedium,
            color = InkMist.SecondaryText,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text(placeholder, color = InkMist.SecondaryText) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 360.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = InkMist.PrimaryText),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = InkMist.Moonstone,
                unfocusedBorderColor = InkMist.HairlineGlass,
                focusedContainerColor = InkMist.GlassFill,
                unfocusedContainerColor = InkMist.GlassFill,
                cursorColor = InkMist.Moonstone,
                focusedTextColor = InkMist.PrimaryText,
                unfocusedTextColor = InkMist.PrimaryText
            )
        )
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SecondaryButton(if (isAsk) "Cancel" else "Discard", onClick = onDiscard, modifier = Modifier.weight(1f))
            SecondaryButton(if (isAsk) "Re-ask" else "Re-record", onClick = onReRecord, modifier = Modifier.weight(1f))
            Button(
                onClick = onSubmit,
                modifier = Modifier.weight(1.4f),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = InkMist.Moonstone,
                    contentColor = Color.White
                ),
                enabled = text.isNotBlank()
            ) {
                Text(submitLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = InkMist.SoftFillStrong,
            contentColor = InkMist.PrimaryText
        )
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun SuggestionsCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .glassSurface(cornerRadius = 18)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Text(
            "Try one of these",
            style = MaterialTheme.typography.labelLarge,
            color = InkMist.SecondaryText
        )
        Spacer(Modifier.height(8.dp))
        listOf(
            "A meeting note: who decided what, by when",
            "A passing thought you don't want to lose",
            "A new person you met today and one thing about them"
        ).forEach {
            Text(
                "·  $it",
                color = InkMist.PrimaryText.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                modifier = Modifier.padding(vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun CaptureStatusPill(latest: CaptureEntity?, pending: Int, justProcessed: Boolean) {
    val visible = latest != null || pending > 0 || justProcessed
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 })
    ) {
        val text: String
        val color: Color
        when {
            justProcessed && latest?.status == "pending" -> {
                text = "Captured — Cortex is filing it"
                color = InkMist.Moonstone
            }
            pending > 0 -> {
                text = "Filing $pending capture${if (pending > 1) "s" else ""}…"
                color = InkMist.Moonstone
            }
            latest?.status == "processed" -> {
                text = "Last capture filed"
                color = InkMist.Moonstone
            }
            latest?.status == "failed" -> {
                text = "Last capture failed — check your DeepSeek API key"
                color = InkMist.DomainPersonal
            }
            else -> {
                text = "Ready"
                color = InkMist.SecondaryText
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(100))
                .background(color.copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(text, color = color, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The signature breathing orb (FR §8.7). A single `InfiniteTransition`
 * powers all phases, so re-keying the parent (e.g. on RMS updates) does
 * not tear down the animation — the orb stays continuous (Amendment T-A2).
 *
 * In CAPTURE mode the orb glows in sapphire (Moonstone); in ASK mode it
 * shifts to the paler MoonstoneSoft so the owner can tell at a glance
 * which conversation the current voice session belongs to (FR-1.1c).
 */
@Composable
private fun BreathingOrb(
    onClick: () -> Unit,
    scale: Float = 1f,
    mode: VoiceMode = VoiceMode.CAPTURE,
    energetic: Boolean = false
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val breath by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = if (energetic) 1.06f else 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (energetic) 1400 else 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )
    val glow by transition.animateFloat(
        initialValue = if (energetic) 0.75f else 0.55f,
        targetValue = if (energetic) 1.0f else 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (energetic) 1400 else 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    val accent = if (mode == VoiceMode.ASK) InkMist.MoonstoneSoft else InkMist.Moonstone
    val core = accent

    Box(
        modifier = Modifier
            .size(280.dp)
            .scale(breath * scale)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.28f * glow),
                        accent.copy(alpha = 0.08f * glow),
                        Color.Transparent
                    )
                )
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // Mid wash
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.55f),
                            accent.copy(alpha = 0.20f),
                            Color.Transparent
                        )
                    )
                )
        )
        // Jewel core: bright crown highlight at the top, sapphire body below.
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.85f * glow),
                            core,
                            core.copy(alpha = 0.85f)
                        )
                    )
                )
                .border(1.dp, core.copy(alpha = 0.45f), CircleShape)
        )
    }
}
