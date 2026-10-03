package com.example.indicconformer

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    val audioCapture = AudioCapture()
    val vad = VAD()
    val sttEngine = STTEngine()
    lateinit var modelManager: ModelManager
    val metricsManager = MetricsManager()
    val sentenceManager = SentenceManager()
    lateinit var ttsManager: OfflineTTSManager
    lateinit var playbackManager: AudioPlaybackManager

    var isModelLoaded = mutableStateOf(false)
    var modelLoadError = mutableStateOf<String?>(null)
    var isRecording = mutableStateOf(false)
    var partialText = mutableStateOf("")
    var activeLanguage = mutableStateOf(Language.MARATHI)
    var sendPriority = mutableStateOf(MessagePriority.NORMAL)
    var isWalkieTalkieMode = mutableStateOf(true)

    fun setWalkieTalkieMode(enabled: Boolean) {
        isWalkieTalkieMode.value = enabled
        getSharedPreferences("walkie_talkie_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("key_walkie_talkie_mode", enabled)
            .apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        val walkiePrefs = getSharedPreferences("walkie_talkie_prefs", Context.MODE_PRIVATE)
        isWalkieTalkieMode.value = walkiePrefs.getBoolean("key_walkie_talkie_mode", true)
        modelManager = ModelManager(this)
        ttsManager = OfflineTTSManager(this)
        playbackManager = AudioPlaybackManager(this, ttsManager, lifecycleScope)
        activeLanguage.value = modelManager.getActiveLanguage()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("IndicConformer", "FATAL CRASH on thread ${thread.name}", throwable)
            try {
                val crashFile = File(getExternalFilesDir(null), "crash_log.txt")
                crashFile.writeText("Thread: ${thread.name}\n${throwable.stackTraceToString()}")
            } catch (e: Exception) {
                Log.e("IndicConformer", "Failed to write crash log", e)
            }
            System.exit(1)
        }

        thread { initModel() }

        setContent {
            MarathiVoiceTheme {
                MarathiVoiceApp(this)
            }
        }
    }

    private fun initModel() {
        val t0 = SystemClock.uptimeMillis()
        try {
            val lang = activeLanguage.value
            val success = if (lang.code.equals("mr", ignoreCase = true)) {
                val (modelFile, tokensFile) = modelManager.prepareModelFiles("model.int8.onnx", "tokens.txt", 197595593L)
                sttEngine.loadModel(modelFile.absolutePath, tokensFile.absolutePath)
                metricsManager.modelFileSize = modelFile.length()
                true
            } else {
                modelManager.loadLanguageModel(lang.code, sttEngine)
            }
            if (success) {
                metricsManager.modelLoadingTimeMs = SystemClock.uptimeMillis() - t0
                isModelLoaded.value = true
                Log.d("IndicConformer", "Model for ${lang.displayName} loaded in ${metricsManager.modelLoadingTimeMs} ms")
            } else {
                modelLoadError.value = "Failed to load ${lang.displayName} model"
            }
        } catch (t: Throwable) {
            Log.e("IndicConformer", "Error loading model", t)
            modelLoadError.value = t.message ?: "Unknown error"
        }
    }

    fun startRecording(onNewMessage: (VoiceMessage, SpeechResult) -> Unit) {
        if (!audioCapture.start()) return
        isRecording.value = true
        partialText.value = ""
        metricsManager.startUtterance()
        
        thread {
            val shortBuffer = ShortArray(1600)
            val audioBuffer = mutableListOf<Float>()
            var lastPartialTime = 0L

            while (audioCapture.isRecording) {
                val readResult = audioCapture.read(shortBuffer)
                if (readResult > 0) {
                    val currentTimeMs = SystemClock.uptimeMillis()
                    val floatBuffer = vad.processAudio(shortBuffer, readResult, currentTimeMs)
                    
                    val isPtt = isWalkieTalkieMode.value
                    if (isPtt || vad.isSpeaking) {
                        audioBuffer.addAll(floatBuffer.toList())
                        
                        if (currentTimeMs - lastPartialTime > 300) {
                            val samples = audioBuffer.toFloatArray()
                            val (text, dur) = sttEngine.decodeBuffer(samples)
                            metricsManager.addInference(dur, samples.size / 16L)
                            metricsManager.recordFirstPartialLatency(currentTimeMs - lastPartialTime)
                            runOnUiThread { partialText.value = text }
                            lastPartialTime = currentTimeMs
                        }

                        if (!isPtt && vad.checkSilence(currentTimeMs)) {
                            finalizeSentence(audioBuffer, onNewMessage)
                        }
                    }
                }
            }
            vad.forceStop()
            if (audioBuffer.isNotEmpty()) {
                finalizeSentence(audioBuffer, onNewMessage)
            } else {
                runOnUiThread { 
                    partialText.value = "" 
                    isRecording.value = false
                }
            }
        }
    }

    fun stopRecording() {
        if (audioCapture.isRecording) audioCapture.stop()
        isRecording.value = false
    }

    private fun finalizeSentence(audioBuffer: MutableList<Float>, onNewMessage: (VoiceMessage, SpeechResult) -> Unit) {
        val t0 = SystemClock.uptimeMillis()
        val samples = audioBuffer.toFloatArray()
        val (text, dur) = sttEngine.decodeBuffer(samples)
        metricsManager.addInference(dur, samples.size / 16L)
        metricsManager.speechEndToFinalLatencyMs = SystemClock.uptimeMillis() - t0
        
        val currentLang = activeLanguage.value.code
        val priority = sendPriority.value
        val msgType = if (priority == MessagePriority.ALERT) MessageType.ALERT else MessageType.SPEECH
        val result = sentenceManager.createResult(text, true, currentLang, priority, msgType)
        runOnUiThread {
            if (result.text.isNotBlank()) {
                metricsManager.logUtteranceMetrics(result.text)
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(result.timestamp))
                val msg = VoiceMessage(
                    text = result.text,
                    sent = true,
                    duration = String.format("%.1fs", metricsManager.totalAudioDurationMs / 1000f),
                    time = timeStr,
                    id = result.messageId,
                    originalText = null,
                    sourceLanguage = currentLang,
                    translatedLanguage = null,
                    priority = priority,
                    messageType = msgType
                )
                onNewMessage(msg, result)
            }
            partialText.value = ""
            isRecording.value = false
        }
        audioBuffer.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        playbackManager.release()
        audioCapture.stop()
        sttEngine.unload()
    }
}

enum class Screen { Splash, Role, Connect, Home, Chat, Device, Settings }
enum class Role { Sender, Receiver }
enum class VoiceState { Idle, Listening, Review, Playing }

data class VoiceMessage(
    val text: String,
    val sent: Boolean,
    val duration: String,
    val time: String,
    val id: String = UUID.randomUUID().toString(),
    val originalText: String? = null,
    val sourceLanguage: String = "mr",
    val translatedLanguage: String? = null,
    val priority: MessagePriority = MessagePriority.NORMAL,
    val messageType: MessageType = MessageType.SPEECH
)

private fun transportPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
    arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.RECORD_AUDIO)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO)
}

@Composable
fun MarathiVoiceApp(activity: MainActivity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transport = remember { DeviceTransport(context) }
    val transportState by transport.state.collectAsState()
    val incomingResult by transport.incomingResults.collectAsState()

    val downloader = remember { ModelDownloader(context, scope) }
    val downloadState by downloader.state.collectAsState()
    val activeLanguage by activity.activeLanguage
    var showFirstLaunchDialog by remember { mutableStateOf(activity.modelManager.isFirstLaunch()) }

    var screen by remember { mutableStateOf(Screen.Splash) }
    var role by remember { mutableStateOf(Role.Sender) }
    var messages by remember { mutableStateOf(listOf<VoiceMessage>()) }
    
    val isModelLoaded by activity.isModelLoaded
    val modelLoadError by activity.modelLoadError
    val isRecording by activity.isRecording
    val partialText by activity.partialText
    val sendPriority by activity.sendPriority
    val isWalkieTalkieMode by activity.isWalkieTalkieMode

    // Phase 4 Audio Playback & Offline TTS state tracking
    val isAutoPlayEnabled by activity.playbackManager.isAutoPlayEnabled.collectAsState()
    val currentlyPlayingId by activity.playbackManager.currentlyPlayingId.collectAsState()
    val playbackStates by activity.playbackManager.playbackStates.collectAsState()
    val queueSize by activity.playbackManager.queueSize.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            transport.start(roleIsSender = role == Role.Sender, mode = TransportMode.HOTSPOT_WIFI)
        }
    }
    val connected = transportState is TransportState.Connected

    // Hardware & gesture back button support for smooth mode switching
    BackHandler(enabled = screen != Screen.Splash) {
        when (screen) {
            Screen.Role -> screen = Screen.Splash
            Screen.Connect -> screen = Screen.Role
            Screen.Home -> screen = Screen.Role
            Screen.Chat -> screen = Screen.Home
            Screen.Device -> screen = Screen.Home
            Screen.Settings -> screen = Screen.Home
            Screen.Splash -> {}
        }
    }

    DisposableEffect(transport) {
        onDispose { transport.close() }
    }

    // Reactively receive incoming speech messages over TCP with offline cross-language translation
    LaunchedEffect(incomingResult) {
        incomingResult?.let { result ->
            if (messages.none { it.id == result.messageId }) {
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(result.timestamp))
                val targetLangCode = activeLanguage.code
                val isDifferentLanguage = !result.sourceLanguage.equals(targetLangCode, ignoreCase = true)
                val displayedText = if (isDifferentLanguage) {
                    TranslationEngine.translate(result.text, result.sourceLanguage, targetLangCode)
                } else {
                    result.text
                }
                val msg = VoiceMessage(
                    text = displayedText,
                    sent = false,
                    duration = "--",
                    time = timeStr,
                    id = result.messageId,
                    originalText = if (isDifferentLanguage) result.text else null,
                    sourceLanguage = result.sourceLanguage,
                    translatedLanguage = if (isDifferentLanguage) targetLangCode else null,
                    priority = result.priority,
                    messageType = result.messageType
                )
                messages = listOf(msg) + messages

                // Trigger offline TTS voice-note playback on receiver
                activity.playbackManager.onMessageReceived(
                    messageId = result.messageId,
                    text = displayedText,
                    languageCode = targetLangCode,
                    priority = result.priority
                )
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = Ink) {
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            AnimatedContent(targetState = screen, label = "screen") { target ->
            when (target) {
                Screen.Splash -> SplashScreen { screen = Screen.Role }
                Screen.Role -> RoleScreen(
                    activity = activity,
                    downloader = downloader,
                    downloadState = downloadState,
                    onSelect = { selected ->
                        role = selected
                        screen = Screen.Connect
                        permissionLauncher.launch(transportPermissions())
                    },
                    onSettings = { screen = Screen.Settings },
                    onBack = { screen = Screen.Splash }
                )
                Screen.Connect -> ConnectionScreen(
                    role = role,
                    state = transportState,
                    transport = transport,
                    onStart = { mode -> transport.start(role == Role.Sender, mode) },
                    onConnectIp = { ip -> transport.connectToIp(ip) },
                    onReconnect = { transport.reconnect() },
                    onContinue = { screen = Screen.Home },
                    onBack = { screen = Screen.Role }
                )
                Screen.Home -> HomeScreen(
                    role = role,
                    activeLanguage = activeLanguage,
                    connected = connected,
                    transportState = transportState,
                    voiceState = if (isRecording) VoiceState.Listening else VoiceState.Idle,
                    latest = messages.firstOrNull(),
                    playbackState = messages.firstOrNull()?.let { playbackStates[it.id] ?: PlaybackState.IDLE } ?: PlaybackState.IDLE,
                    isAutoPlayEnabled = isAutoPlayEnabled,
                    queueSize = queueSize,
                    sendPriority = sendPriority,
                    onPriorityChange = { activity.sendPriority.value = it },
                    isWalkieTalkieMode = isWalkieTalkieMode,
                    onWalkieTalkieModeChange = { activity.setWalkieTalkieMode(it) },
                    onVoiceStart = {
                        activity.startRecording { msg, speechResult -> 
                            messages = listOf(msg) + messages
                            transport.send(speechResult)
                        }
                    },
                    onVoiceStop = { activity.stopRecording() },
                    onNavigate = { screen = it },
                    onSettings = { screen = Screen.Settings },
                    onSwitchMode = { screen = Screen.Role },
                    isModelLoaded = isModelLoaded,
                    partialText = partialText,
                    modelLoadError = modelLoadError,
                    onPlayLatest = { msg ->
                        activity.playbackManager.play(msg.id, msg.text, msg.translatedLanguage ?: activeLanguage.code, msg.priority)
                    },
                    onPausePlayback = { activity.playbackManager.pause() },
                    onStopPlayback = { activity.playbackManager.stopCurrent() },
                    onReplayLatest = { msg ->
                        activity.playbackManager.replay(msg.id, msg.text, msg.translatedLanguage ?: activeLanguage.code)
                    },
                    onToggleAutoPlay = { activity.playbackManager.setAutoPlay(!isAutoPlayEnabled) },
                    onClearQueue = { activity.playbackManager.clearQueue() }
                )
                Screen.Chat -> ChatScreen(
                    messages = messages,
                    playbackStates = playbackStates,
                    onPlay = { msg ->
                        activity.playbackManager.play(msg.id, msg.text, msg.translatedLanguage ?: msg.sourceLanguage, msg.priority)
                    },
                    onPause = { activity.playbackManager.pause() },
                    onStop = { activity.playbackManager.stopCurrent() },
                    onReplay = { msg ->
                        activity.playbackManager.replay(msg.id, msg.text, msg.translatedLanguage ?: msg.sourceLanguage)
                    },
                    onNavigate = { screen = it },
                    onBack = { screen = Screen.Home }
                )
                Screen.Device -> DeviceScreen(
                    connected = connected,
                    state = transportState,
                    onReconnect = { transport.reconnect() },
                    onNavigate = { screen = it },
                    onBack = { screen = Screen.Home }
                )
                Screen.Settings -> SettingsScreen(
                    activity = activity,
                    downloader = downloader,
                    downloadState = downloadState,
                    onBack = { screen = Screen.Home }
                )
            }
        }

        if (showFirstLaunchDialog) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(0.75f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier
                        .fillMaxWidth(0.9f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(SurfaceWhite)
                        .border(1.dp, Border, RoundedCornerShape(24.dp))
                        .padding(22.dp)
                ) {
                    Text("Select Primary Language", color = TextDark, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Choose your default language. Marathi is pre-installed for 100% offline use. Other 9 SIH languages can be downloaded anytime from Settings.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    LazyColumn(Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(Language.ALL) { lang ->
                            val isSelected = activeLanguage.code == lang.code
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) SurfaceButter else SurfaceRaised)
                                    .border(1.dp, if (isSelected) BorderButter else Border, RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (lang.code == "mr" || activity.modelManager.isLanguageInstalled(lang.code)) {
                                            thread {
                                                activity.isModelLoaded.value = false
                                                val ok = activity.modelManager.loadLanguageModel(lang.code, activity.sttEngine)
                                                activity.runOnUiThread {
                                                    if (ok) activity.activeLanguage.value = lang
                                                    activity.isModelLoaded.value = ok
                                                }
                                            }
                                        }
                                        activity.modelManager.setActiveLanguage(lang)
                                        activity.activeLanguage.value = lang
                                        activity.modelManager.setFirstLaunchDone()
                                        showFirstLaunchDialog = false
                                    }
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(lang.nativeName, color = TextDark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text(lang.displayName, color = TextMuted, fontSize = 12.sp)
                                }
                                if (lang.code == "mr") {
                                    Text("Pre-installed", color = Success, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Text(if (activity.modelManager.isLanguageInstalled(lang.code)) "Installed" else "On-demand", color = TextAmber, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            activity.modelManager.setFirstLaunchDone()
                            showFirstLaunchDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Rust)
                    ) {
                        Text("CONTINUE WITH MARATHI", color = PureWhite, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
}

@Composable
private fun SplashScreen(onStart: () -> Unit) {
    Box(Modifier.fillMaxSize().background(AppBg).padding(28.dp)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "App Logo",
                modifier = Modifier.size(150.dp)
            )
            Spacer(Modifier.height(26.dp))
            Text("SWARTARANG", color = TextDark, fontSize = 38.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text("VOICE COMMUNICATION", color = TextAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(Modifier.width(90.dp), color = Rust.copy(.4f))
            Spacer(Modifier.height(18.dp))
            Text("Speak. Connect.\nCommunicate.", color = TextMuted, fontSize = 19.sp, textAlign = TextAlign.Center, lineHeight = 28.sp)
            Spacer(Modifier.height(44.dp))
            PrimaryButton("GET STARTED  →", onStart)
            Spacer(Modifier.height(18.dp))
            Text("WORKS COMPLETELY OFFLINE", color = TextMuted, fontSize = 11.sp, letterSpacing = 1.8.sp)
        }
    }
}

@Composable
private fun RoleScreen(
    activity: MainActivity,
    downloader: ModelDownloader,
    downloadState: DownloadState,
    onSelect: (Role) -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit
) {
    val activeLanguage by activity.activeLanguage
    var showModelPicker by remember { mutableStateOf(false) }
    var switchingLangCode by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Setting button on Sender receiver button page via TopBar
            TopBar("SWARTARANG", onBack = onBack, onAction = onSettings)
            Spacer(Modifier.height(14.dp))

            // Option to change model (e.g. from Hindi to English) with small button
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceWhite)
                    .border(1.dp, Border, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceButter)
                            .border(1.dp, BorderButter, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(activeLanguage.code.uppercase(), color = TextAmber, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("RECOGNITION MODEL", color = TextMuted, fontSize = 9.sp, letterSpacing = 1.sp)
                        Text(activeLanguage.displayName, color = TextDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Button(
                    onClick = { showModelPicker = true },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ButterYellow),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.Language, contentDescription = null, tint = TextDark, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("CHANGE", color = TextDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("HOW DO YOU WANT TO CONNECT?", color = TextDark, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("Choose your role for this device", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(18.dp))
            RoleCard("🎙", "SENDER", "Speak in ${activeLanguage.displayName} & send speech", Rust, { onSelect(Role.Sender) })
            Spacer(Modifier.height(12.dp))
            RoleCard("🔊", "RECEIVER", "Listen & read incoming speech (${activeLanguage.displayName})", ButterYellow, { onSelect(Role.Receiver) })
            Spacer(Modifier.height(18.dp))
            Text("OFFLINE  •  PRIVATE  •  DEVICE-TO-DEVICE", color = TextMuted, fontSize = 10.sp, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(10.dp))
        }

        // In-composition Model Picker Sheet / Modal with download button if model not available
        if (showModelPicker) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(0.75f))
                    .clickable { showModelPicker = false },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier
                        .fillMaxWidth(0.92f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(SurfaceWhite)
                        .border(1.dp, Border, RoundedCornerShape(24.dp))
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                        .padding(20.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Select / Download Model", color = TextDark, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { showModelPicker = false }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(
                        "Switch language model (e.g. Hindi, English, Marathi). If not installed, tap the download button to download offline model.",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    LazyColumn(
                        Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(Language.ALL) { lang ->
                            val isActive = activeLanguage.code == lang.code
                            val isInstalled = activity.modelManager.isLanguageInstalled(lang.code)
                            val isSwitching = switchingLangCode == lang.code
                            val isDownloading = downloadState is DownloadState.Downloading && downloadState.langCode == lang.code
                            val isVerifying = downloadState is DownloadState.Verifying && downloadState.langCode == lang.code

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isActive) SurfaceButter else SurfaceRaised)
                                    .border(1.dp, if (isActive) BorderButter else Border, RoundedCornerShape(12.dp))
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(lang.nativeName, color = TextDark, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Spacer(Modifier.width(6.dp))
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(SurfaceButter)
                                                .border(1.dp, BorderButter, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                        ) {
                                            Text(lang.code.uppercase(), color = TextAmber, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Text(lang.displayName, color = TextMuted, fontSize = 11.sp)

                                    if (isDownloading) {
                                        val progress = (downloadState as DownloadState.Downloading).progress
                                        Spacer(Modifier.height(6.dp))
                                        LinearProgressIndicator(
                                            progress = { progress },
                                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                            color = Cyan,
                                            trackColor = Color.White.copy(0.1f)
                                        )
                                        Text("${(progress * 100).toInt()}% • downloading...", color = Cyan, fontSize = 9.sp)
                                    } else if (isVerifying) {
                                        Spacer(Modifier.height(4.dp))
                                        Text((downloadState as DownloadState.Verifying).message, color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(Modifier.width(8.dp))

                                when {
                                    isActive -> {
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Success.copy(0.2f))
                                                .border(1.dp, Success.copy(0.8f), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("ACTIVE", color = Success, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    isSwitching -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = Cyan,
                                            strokeWidth = 2.dp
                                        )
                                    }
                                    isInstalled -> {
                                        Button(
                                            onClick = {
                                                switchingLangCode = lang.code
                                                thread {
                                                    activity.isModelLoaded.value = false
                                                    val ok = activity.modelManager.loadLanguageModel(lang.code, activity.sttEngine)
                                                    activity.runOnUiThread {
                                                        if (ok) {
                                                            activity.activeLanguage.value = lang
                                                            activity.isModelLoaded.value = true
                                                            activity.modelLoadError.value = null
                                                        } else {
                                                            activity.modelLoadError.value = "Failed to load ${lang.displayName}"
                                                        }
                                                        switchingLangCode = null
                                                        showModelPicker = false
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = ButterYellow),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("SELECT", color = TextDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    isDownloading || isVerifying -> {
                                        OutlinedButton(
                                            onClick = { downloader.cancelDownload() },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Text("CANCEL", color = Color(0xFFFF8E9E), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    else -> {
                                        Button(
                                            onClick = {
                                                downloader.downloadLanguage(lang.code)
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Rust),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Icon(Icons.Default.CloudDownload, contentDescription = null, tint = PureWhite, modifier = Modifier.size(12.dp))
                                            Spacer(Modifier.width(3.dp))
                                            Text("DOWNLOAD", color = PureWhite, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    SecondaryButton(
                        text = "OPEN FULL SETTINGS HUB  ⚙",
                        onClick = {
                            showModelPicker = false
                            onSettings()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionScreen(
    role: Role,
    state: TransportState,
    transport: DeviceTransport,
    onStart: (TransportMode) -> Unit,
    onConnectIp: (String) -> Unit,
    onReconnect: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    val connected = state is TransportState.Connected
    var selectedMode by remember { mutableStateOf(TransportMode.HOTSPOT_WIFI) }
    var targetIp by remember { mutableStateOf(transport.getSuggestedPeerIp()) }
    val myIp = transport.getMyIp()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopBar("CONNECT", onBack = onBack)
        Spacer(Modifier.height(14.dp))

        val statusLabel = when (state) {
            is TransportState.Error -> "ERROR: ${state.message.uppercase()}"
            is TransportState.ConnectionLost -> "CONNECTION LOST: ${state.reason.uppercase()}"
            is TransportState.Connected -> "● SECURE LINK: ${if (state.via == LinkType.WIFI_DIRECT) "WI-FI DIRECT" else "LOCAL WI-FI"}"
            is TransportState.PeerFound -> "PEER FOUND: ${state.name.uppercase()}"
            is TransportState.Connecting -> state.details.uppercase()
            TransportState.Discovering -> if (selectedMode == TransportMode.HOTSPOT_WIFI) "BROADCASTING & LISTENING ON LOCAL MESH..." else "SEARCHING WI-FI DIRECT PEERS..."
            TransportState.Idle -> if (role == Role.Sender) "READY TO CONNECT (SENDER)" else "READY TO CONNECT (RECEIVER)"
        }
        Text(
            statusLabel,
            color = if (connected) Success else if (state is TransportState.Error || state is TransportState.ConnectionLost) Color(0xFFFF8E9E) else SoftWhite,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.3.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        ConnectionVisual(connected)
        Spacer(Modifier.height(18.dp))

        if (connected) {
            val connection = state as TransportState.Connected
            Text("✓  CONNECTED", color = Success, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("${connection.peerName}  •  ${connection.via.name.replace('_', ' ')} (${connection.address})", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(24.dp))
            PrimaryButton("CONTINUE TO VOICE  →", onContinue, modifier = Modifier.fillMaxWidth())
        } else if (state is TransportState.ConnectionLost || state is TransportState.Error) {
            GlassPanel {
                Text("CONNECTION FAILED OR DROPPED", color = Color(0xFFFF8E9E), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Recommendation: Turn on Hotspot on Phone 1, connect Phone 2 to it via Wi-Fi, then tap Auto-Connect.", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton("RETRY AUTO-CONNECT", onReconnect, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            SecondaryButton("USE OFFLINE DIRECTLY  →", onContinue, modifier = Modifier.fillMaxWidth())
        } else {
            // Mode Selector Tabs (Hotspot/Wi-Fi vs Wi-Fi Direct)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceWhite)
                    .border(1.dp, Border, RoundedCornerShape(14.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selectedMode == TransportMode.HOTSPOT_WIFI) Rust else Color.Transparent)
                        .clickable { selectedMode = TransportMode.HOTSPOT_WIFI }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "HOTSPOT / WI-FI",
                        color = if (selectedMode == TransportMode.HOTSPOT_WIFI) PureWhite else TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selectedMode == TransportMode.WIFI_DIRECT) Rust else Color.Transparent)
                        .clickable { selectedMode = TransportMode.WIFI_DIRECT }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "WI-FI DIRECT",
                        color = if (selectedMode == TransportMode.WIFI_DIRECT) PureWhite else TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (selectedMode == TransportMode.HOTSPOT_WIFI) {
                GlassPanel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (role == Role.Sender) "YOUR DEVICE (SENDER)" else "YOUR DEVICE (RECEIVER)", color = TextAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text("IP: $myIp", color = TextDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "1. Turn ON Hotspot on Phone 1.\n2. Connect Phone 2 to Hotspot via Wi-Fi.\n3. Tap Auto-Connect (or enter IP directly).",
                        color = TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                Spacer(Modifier.height(14.dp))
                PrimaryButton(
                    text = "⚡ AUTO-CONNECT (HOTSPOT / WI-FI)",
                    onClick = { onStart(TransportMode.HOTSPOT_WIFI) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))

                // Direct IP connect card for instant pairing
                GlassPanel {
                    Text("DIRECT IP CONNECT", color = TextAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = targetIp,
                        onValueChange = { targetIp = it },
                        label = { Text("Peer IP (e.g. 192.168.43.1)", color = TextMuted, fontSize = 11.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextDark,
                            unfocusedTextColor = TextDark,
                            focusedBorderColor = Rust,
                            unfocusedBorderColor = Border,
                            focusedLabelColor = Rust,
                            unfocusedLabelColor = TextMuted,
                            cursorColor = Rust
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { targetIp = "192.168.43.1" },
                            modifier = Modifier.weight(1f).height(38.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Border),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Use 192.168.43.1", fontSize = 10.sp, color = Rust, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { onConnectIp(targetIp) },
                            modifier = Modifier.weight(1f).height(38.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ButterYellow),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("CONNECT IP", fontSize = 11.sp, color = TextDark, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                GlassPanel {
                    Text("WI-FI DIRECT (P2P)", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Radio-to-radio direct connection. Use ONLY if you do not have Hotspot or a Wi-Fi router enabled.\nKeep Wi-Fi & Location ON.",
                        color = Muted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
                Spacer(Modifier.height(14.dp))
                PrimaryButton(
                    text = if (role == Role.Sender) "START WI-FI DIRECT GROUP" else "SCAN WI-FI DIRECT PEERS",
                    onClick = { onStart(TransportMode.WIFI_DIRECT) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(12.dp))
            SecondaryButton("USE OFFLINE DIRECTLY  →", onContinue, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("← SWITCH ROLE / CHOOSE MODE", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HomeScreen(
    role: Role,
    activeLanguage: Language,
    connected: Boolean,
    transportState: TransportState,
    voiceState: VoiceState,
    latest: VoiceMessage?, 
    playbackState: PlaybackState,
    isAutoPlayEnabled: Boolean,
    queueSize: Int,
    sendPriority: MessagePriority,
    onPriorityChange: (MessagePriority) -> Unit,
    isWalkieTalkieMode: Boolean,
    onWalkieTalkieModeChange: (Boolean) -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit, 
    onNavigate: (Screen) -> Unit,
    onSettings: () -> Unit,
    onSwitchMode: () -> Unit,
    isModelLoaded: Boolean,
    partialText: String,
    modelLoadError: String?,
    onPlayLatest: (VoiceMessage) -> Unit,
    onPausePlayback: () -> Unit,
    onStopPlayback: () -> Unit,
    onReplayLatest: (VoiceMessage) -> Unit,
    onToggleAutoPlay: () -> Unit,
    onClearQueue: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 10.dp)) {
        // TopBar with dedicated back button on the left to switch roles anytime!
        TopBar(
            title = "SWARTARANG",
            onBack = onSwitchMode,
            onAction = onSettings,
            status = connected
        )
        
        // Mode badge & Switch Mode button row
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape).background(if (role == Role.Sender) Violet else Cyan)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (role == Role.Sender) "SENDER MODE" else "RECEIVER MODE",
                    color = if (role == Role.Sender) Violet else Cyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp
                )
            }
            OutlinedButton(
                onClick = onSwitchMode,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Border),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Icon(Icons.Default.Sync, contentDescription = null, tint = TextDark, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text("SWITCH MODE", color = TextDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Active Language selector card
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceWhite)
                .border(1.dp, Border, RoundedCornerShape(12.dp))
                .clickable { onSettings() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(SurfaceButter)
                        .border(1.dp, BorderButter, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(activeLanguage.code.uppercase(), color = TextAmber, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    activeLanguage.displayName.uppercase(),
                    color = TextDark,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
            Text("CHANGE ⚙", color = Rust, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }

        // Dynamic transport connection banner
        if (transportState is TransportState.Connected) {
            val viaText = if (transportState.via == LinkType.WIFI_DIRECT) "WI-FI DIRECT" else "LOCAL WI-FI"
            Text(
                "● CONNECTED VIA $viaText (${transportState.address})",
                color = Success,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            Text(
                "● OFFLINE STANDALONE MODE",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (!isModelLoaded && modelLoadError == null) {
            Text("LOADING AI MODEL...", color = WarmAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(top = 28.dp))
        } else if (modelLoadError != null) {
            Text("ERROR: $modelLoadError", color = Color(0xFFFF8E9E), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 28.dp))
        } else if (role == Role.Sender) {
            Text("READY TO COMMUNICATE?", color = TextAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 16.dp))
            Text("Speak in ${activeLanguage.displayName}", color = TextDark, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
            
            // Priority Selector: NORMAL vs EMERGENCY ALERT
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (sendPriority == MessagePriority.NORMAL) SurfaceButter else SurfaceWhite,
                    border = BorderStroke(1.dp, if (sendPriority == MessagePriority.NORMAL) BorderButter else Border),
                    modifier = Modifier.clickable { onPriorityChange(MessagePriority.NORMAL) }
                ) {
                    Text(
                        "NORMAL SPEECH",
                        color = if (sendPriority == MessagePriority.NORMAL) TextDark else TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (sendPriority == MessagePriority.ALERT) AlertRed.copy(0.12f) else SurfaceWhite,
                    border = BorderStroke(1.dp, if (sendPriority == MessagePriority.ALERT) AlertRed else Border),
                    modifier = Modifier.clickable { onPriorityChange(MessagePriority.ALERT) }
                ) {
                    Text(
                        "🚨 EMERGENCY ALERT",
                        color = if (sendPriority == MessagePriority.ALERT) AlertRed else TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            // Walkie-Talkie (PTT) vs Phone Mode (Hands-Free) Toggle Pill
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceWhite)
                    .border(1.dp, Border, RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isWalkieTalkieMode) SurfaceButter else Color.Transparent,
                    border = BorderStroke(1.dp, if (isWalkieTalkieMode) BorderButter else Color.Transparent),
                    modifier = Modifier.weight(1f).clickable { onWalkieTalkieModeChange(true) }
                ) {
                    Row(
                        Modifier.padding(vertical = 7.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Radio, contentDescription = null, tint = if (isWalkieTalkieMode) TextDark else TextMuted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("PTT WALKIE-TALKIE", color = if (isWalkieTalkieMode) TextDark else TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(4.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (!isWalkieTalkieMode) SurfaceButter else Color.Transparent,
                    border = BorderStroke(1.dp, if (!isWalkieTalkieMode) BorderButter else Color.Transparent),
                    modifier = Modifier.weight(1f).clickable { onWalkieTalkieModeChange(false) }
                ) {
                    Row(
                        Modifier.padding(vertical = 7.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PhoneInTalk, contentDescription = null, tint = if (!isWalkieTalkieMode) TextDark else TextMuted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("PHONE (HANDS-FREE)", color = if (!isWalkieTalkieMode) TextDark else TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { 
                VoiceOrb(
                    size = 196.dp,
                    active = voiceState != VoiceState.Idle,
                    isWalkieTalkie = isWalkieTalkieMode,
                    onStart = onVoiceStart,
                    onStop = onVoiceStop
                ) 
            }
            Text(
                if (isWalkieTalkieMode) {
                    if (voiceState == VoiceState.Listening) "🎙 TRANSMITTING • RELEASE TO SEND" else "📻 HOLD TO TALK (PTT)"
                } else {
                    if (voiceState == VoiceState.Listening) "📞 CALL ACTIVE • AUTO-VAD (TAP TO END)" else "📞 TAP TO START PHONE CALL"
                },
                color = if (voiceState == VoiceState.Listening) Rust else TextDark,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))
            if (partialText.isNotEmpty()) {
                GlassPanel {
                    Text("LIVE TRANSCRIPT (${activeLanguage.code.uppercase()})", color = TextAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(partialText, color = TextDark, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                latest?.let { RecentCard(it) }
            }
        } else {
            // RECEIVER MODE UI
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("INCOMING SPEECH", color = TextAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text(activeLanguage.displayName, color = TextDark, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                
                // Auto-Play toggle pill button
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isAutoPlayEnabled) SurfaceButter else SurfaceWhite,
                    border = BorderStroke(1.dp, if (isAutoPlayEnabled) BorderButter else Border),
                    modifier = Modifier.clickable { onToggleAutoPlay() }
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isAutoPlayEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = null,
                            tint = if (isAutoPlayEnabled) TextAmber else TextMuted,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (isAutoPlayEnabled) "AUTO-PLAY: ON" else "AUTO-PLAY: OFF",
                            color = if (isAutoPlayEnabled) TextDark else TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Latest Message / Voice Note Panel
            GlassPanel {
                // Header Row
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (latest != null) "VOICE NOTE • ${latest.time}" else "STATUS",
                            color = TextAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                        if (latest?.priority == MessagePriority.ALERT) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFE53935))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("🚨 ALERT", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }

                    if (latest != null && latest.originalText != null && latest.originalText != latest.text) {
                        val srcName = Language.fromCode(latest.sourceLanguage).nativeName
                        val tgtName = Language.fromCode(latest.translatedLanguage ?: activeLanguage.code).nativeName
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Violet.copy(alpha = 0.4f))
                                .border(1.dp, Cyan.copy(0.6f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                "TRANSLATED ($srcName ➔ $tgtName)",
                                color = Cyan,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Received Message Text
                Text(
                    latest?.text ?: "No message received yet\n(Waiting for sender to speak...)",
                    color = if (latest != null) SoftWhite else Muted,
                    fontSize = if (latest != null) 22.sp else 16.sp,
                    fontWeight = FontWeight.Bold
                )

                // Optional Original Speech Expandable
                if (latest != null && latest.originalText != null && latest.originalText != latest.text) {
                    Spacer(Modifier.height(8.dp))
                    var showOrig by remember(latest.id) { mutableStateOf(false) }
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showOrig = !showOrig }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (showOrig) "▲ Hide Original (${Language.fromCode(latest.sourceLanguage).displayName})" else "▼ View Original (${Language.fromCode(latest.sourceLanguage).displayName})",
                            color = Cyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (showOrig) {
                        Text(
                            "\"${latest.originalText}\"",
                            color = Muted,
                            fontSize = 15.sp,
                            fontStyle = FontStyle.Italic,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                // Voice Note Playback Controls on Received Card
                if (latest != null) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = Violet.copy(alpha = 0.3f), thickness = 1.dp)
                    Spacer(Modifier.height(10.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Playback State Badge
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when (playbackState) {
                                PlaybackState.PLAYING -> {
                                    Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Cyan, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("PLAYING OFFLINE...", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                PlaybackState.QUEUED -> {
                                    Icon(Icons.Default.Schedule, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("QUEUED FOR SPEECH", color = Color(0xFFFFB74D), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                PlaybackState.PAUSED -> {
                                    Icon(Icons.Default.Pause, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("PAUSED", color = Color(0xFFFFB74D), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                PlaybackState.COMPLETED -> {
                                    Icon(Icons.Default.DoneAll, contentDescription = null, tint = Success, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("PLAYED", color = Success, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                PlaybackState.FAILED -> {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFFF8E9E), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("TTS FAILED", color = Color(0xFFFF8E9E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                PlaybackState.IDLE -> {
                                    Icon(Icons.Default.Headphones, contentDescription = null, tint = Muted, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("VOICE NOTE", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Playback Action Buttons
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (playbackState) {
                                PlaybackState.PLAYING -> {
                                    IconButton(
                                        onClick = onPausePlayback,
                                        modifier = Modifier.size(34.dp).clip(CircleShape).background(SurfaceWhite).border(1.dp, Border, CircleShape)
                                    ) {
                                        Icon(Icons.Default.Pause, contentDescription = "Pause", tint = TextDark, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = onStopPlayback,
                                        modifier = Modifier.size(34.dp).clip(CircleShape).background(AlertRed.copy(0.12f)).border(1.dp, AlertRed.copy(0.3f), CircleShape)
                                    ) {
                                        Icon(Icons.Default.Stop, contentDescription = "Stop", tint = AlertRed, modifier = Modifier.size(18.dp))
                                    }
                                }
                                PlaybackState.PAUSED -> {
                                    IconButton(
                                        onClick = { onPlayLatest(latest) },
                                        modifier = Modifier.size(34.dp).clip(CircleShape).background(Rust)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = PureWhite, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = { onReplayLatest(latest) },
                                        modifier = Modifier.size(34.dp).clip(CircleShape).background(SurfaceWhite).border(1.dp, Border, CircleShape)
                                    ) {
                                        Icon(Icons.Default.Replay, contentDescription = "Replay", tint = TextDark, modifier = Modifier.size(18.dp))
                                    }
                                }
                                else -> {
                                    IconButton(
                                        onClick = { onPlayLatest(latest) },
                                        modifier = Modifier.size(34.dp).clip(CircleShape).background(Rust)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = PureWhite, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = { onReplayLatest(latest) },
                                        modifier = Modifier.size(34.dp).clip(CircleShape).background(SurfaceWhite).border(1.dp, Border, CircleShape)
                                    ) {
                                        Icon(Icons.Default.Replay, contentDescription = "Replay", tint = TextDark, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Queue status bar if pending messages exist
            if (queueSize > 0) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SurfaceWhite,
                    border = BorderStroke(1.dp, Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.QueueMusic, contentDescription = null, tint = TextAmber, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("QUEUE: $queueSize pending message${if (queueSize > 1) "s" else ""}", color = TextDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(
                            onClick = onClearQueue,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("CLEAR QUEUE", color = AlertRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                VoiceOrb(
                    size = 118.dp,
                    active = playbackState == PlaybackState.PLAYING || latest != null,
                    onStart = {},
                    onStop = {}
                )
            }
            Text(
                when (playbackState) {
                    PlaybackState.PLAYING -> "● SPEAKING VOICE NOTE"
                    PlaybackState.QUEUED -> "● MESSAGE QUEUED"
                    PlaybackState.PAUSED -> "● PLAYBACK PAUSED"
                    else -> if (latest != null) "MESSAGE RECEIVED" else "READY TO RECEIVE"
                },
                color = when (playbackState) {
                    PlaybackState.PLAYING -> Rust
                    PlaybackState.QUEUED, PlaybackState.PAUSED -> WarmAmber
                    else -> TextDark
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.weight(1f))
        BottomNav(Screen.Home, onNavigate)
    }
}

@Composable
private fun ChatScreen(
    messages: List<VoiceMessage>,
    playbackStates: Map<String, PlaybackState>,
    onPlay: (VoiceMessage) -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onReplay: (VoiceMessage) -> Unit,
    onNavigate: (Screen) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 10.dp)) {
        TopBar("CONVERSATION", onBack = onBack, status = true)
        Text("VOICE NOTE LOG", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(top = 22.dp))
        Text("Offline Exchange History", color = SoftWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(18.dp))
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No messages yet. Hold to speak in Sender mode!", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(messages, key = { it.id }) { msg ->
                    MessageCard(
                        message = msg,
                        playbackState = playbackStates[msg.id] ?: PlaybackState.IDLE,
                        onPlay = { onPlay(msg) },
                        onPause = onPause,
                        onStop = onStop,
                        onReplay = { onReplay(msg) }
                    )
                }
            }
        }
        BottomNav(Screen.Chat, onNavigate)
    }
}

@Composable
private fun DeviceScreen(
    connected: Boolean,
    state: TransportState,
    onReconnect: () -> Unit,
    onNavigate: (Screen) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 10.dp)) {
        TopBar("DEVICE", onBack = onBack, status = connected)
        Spacer(Modifier.height(24.dp))
        Text("LOCAL CONNECTION", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text(if (connected) "Connected & Secure" else "No device connected", color = SoftWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(20.dp))
        GlassPanel {
            Text("THIS DEVICE", color = Muted, fontSize = 11.sp, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(6.dp))
            Text(Build.MODEL, color = SoftWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            Text("STATUS", color = Muted, fontSize = 11.sp, letterSpacing = 1.4.sp)
            if (state is TransportState.Connected) {
                val viaName = if (state.via == LinkType.WIFI_DIRECT) "Wi-Fi Direct" else "Local Wi-Fi"
                Text("✓  Connected via $viaName", color = Success, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                Text("Peer: ${state.peerName} (${state.address})", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            } else if (state is TransportState.Discovering) {
                Text("Searching nearby devices...", color = Cyan, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
            } else if (state is TransportState.ConnectionLost) {
                Text("Connection lost: ${state.reason}", color = Color(0xFFFF8E9E), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            } else {
                Text("Offline Standalone", color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        SecondaryButton("RECONNECT / SEARCH PEERS", onReconnect)
        Spacer(Modifier.weight(1f))
        BottomNav(Screen.Device, onNavigate)
    }
}

@Composable
private fun SettingsScreen(
    activity: MainActivity,
    downloader: ModelDownloader,
    downloadState: DownloadState,
    onBack: () -> Unit
) {
    val activeLang by activity.activeLanguage
    var switchingLangCode by remember { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState())
    ) {
        TopBar("SETTINGS & LANGUAGE HUB", onBack = onBack)
        Spacer(Modifier.height(18.dp))

        // Info Banner
        GlassPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Language, contentDescription = null, tint = Cyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("10 SIH LANGUAGES • OFFLINE AI", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Marathi INT8 is bundled offline (~197MB). Additional languages can be downloaded on-demand from Hugging Face Hub (MIT License). Strict 1-Model RAM Isolation ensures zero memory bloat.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "OFFLINE TTS & AUDIO PLAYBACK",
            color = TextAmber,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp
        )
        Spacer(Modifier.height(8.dp))
        GlassPanel {
            val isAutoPlay by activity.playbackManager.isAutoPlayEnabled.collectAsState()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-Play Received Speech", color = TextDark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Speaks incoming voice notes automatically", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Switch(
                    checked = isAutoPlay,
                    onCheckedChange = { activity.playbackManager.setAutoPlay(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PureWhite,
                        checkedTrackColor = ButterYellow,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = SurfaceRaised
                    )
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Border, thickness = 1.dp)
            Spacer(Modifier.height(10.dp))

            val ttsStatus by activity.ttsManager.status.collectAsState()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Local Engine:", color = TextMuted, fontSize = 12.sp)
                Text(
                    when (ttsStatus) {
                        is TTSStatus.Ready -> "✓ READY (100% OFFLINE)"
                        is TTSStatus.Initializing -> "INITIALIZING..."
                        is TTSStatus.Error -> "UNAVAILABLE"
                        is TTSStatus.Uninitialized -> "OFFLINE"
                    },
                    color = when (ttsStatus) {
                        is TTSStatus.Ready -> Success
                        is TTSStatus.Initializing -> WarmAmber
                        else -> AlertRed
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Emergency Alert Volume:", color = TextMuted, fontSize = 12.sp)
                Text("100% MAX (NON-INTERRUPTIBLE)", color = AlertRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Uses Android local speech synthesis. No internet, no external servers, zero API calls.",
                color = TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "ACTIVE RECOGNITION MODEL",
            color = Cyan,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp
        )
        Spacer(Modifier.height(8.dp))
        GlassPanel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        activeLang.displayName,
                        color = SoftWhite,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("IndicConformer INT8 ONNX CTC", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Success.copy(0.2f))
                        .border(1.dp, Success, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("● IN RAM", color = Success, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text(
            "AVAILABLE LANGUAGE MODELS (HUGGING FACE)",
            color = Cyan,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp
        )
        Spacer(Modifier.height(10.dp))

        // List of all 10 languages
        Language.ALL.forEach { lang ->
            val isActive = activeLang.code == lang.code
            val isInstalled = activity.modelManager.isLanguageInstalled(lang.code)
            val isSwitching = switchingLangCode == lang.code
            val isDownloading = downloadState is DownloadState.Downloading && downloadState.langCode == lang.code
            val isVerifying = downloadState is DownloadState.Verifying && downloadState.langCode == lang.code

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isActive) SurfaceButter else SurfaceWhite)
                    .border(1.dp, if (isActive) BorderButter else Border, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(lang.nativeName, color = TextDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(SurfaceButter)
                                    .border(1.dp, BorderButter, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(lang.code.uppercase(), color = TextAmber, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(lang.displayName, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    }

                    when {
                        isActive -> {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Success.copy(0.2f))
                                    .border(1.dp, Success.copy(0.8f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text("ACTIVE", color = Success, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        isSwitching -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Cyan,
                                strokeWidth = 2.dp
                            )
                        }
                        isInstalled -> {
                            Button(
                                onClick = {
                                    switchingLangCode = lang.code
                                    thread {
                                        activity.isModelLoaded.value = false
                                        val ok = activity.modelManager.loadLanguageModel(lang.code, activity.sttEngine)
                                        activity.runOnUiThread {
                                            if (ok) {
                                                activity.activeLanguage.value = lang
                                                activity.isModelLoaded.value = true
                                                activity.modelLoadError.value = null
                                            } else {
                                                activity.modelLoadError.value = "Failed to load ${lang.displayName}"
                                            }
                                            switchingLangCode = null
                                            refreshTrigger++
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ButterYellow),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("ACTIVATE", color = TextDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        isDownloading || isVerifying -> {
                            OutlinedButton(
                                onClick = { downloader.cancelDownload() },
                                shape = RoundedCornerShape(10.dp),
                                border = ButtonDefaults.outlinedButtonBorder(true).copy(
                                    brush = Brush.linearGradient(listOf(Color(0xFFFF6B6B), Color(0xFFFF8E9E)))
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("CANCEL", color = Color(0xFFFF8E9E), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        else -> {
                            Button(
                                onClick = {
                                    downloader.downloadLanguage(lang.code)
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Rust),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = PureWhite, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("DOWNLOAD (~188MB)", color = PureWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // If currently downloading or verifying this language, show progress bar / status
                if (isDownloading) {
                    val progress = (downloadState as DownloadState.Downloading).progress
                    val bytes = downloadState.bytesDownloaded / (1024 * 1024)
                    val total = downloadState.totalBytes / (1024 * 1024)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = Cyan,
                        trackColor = Color.White.copy(0.1f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Downloading ${downloadState.currentFile}", color = Muted, fontSize = 10.sp)
                        Text("${(progress * 100).toInt()}% ($bytes MB / $total MB)", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                } else if (isVerifying) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = Cyan,
                        trackColor = Color.White.copy(0.1f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text((downloadState as DownloadState.Verifying).message, color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SettingGroup("OFFLINE TRANSLATION", listOf(
            "Mode" to "Auto Cross-Language",
            "Dictionary" to "10 SIH Languages Integrated",
            "Internet Dependency" to "0% (Zero Cloud)"
        ))
        SettingGroup("TRANSPORT MESH", listOf(
            "Hotspot Direct TCP" to "Port 8888",
            "Wi-Fi Direct P2P" to "Supported",
            "Framing" to "4-byte Length-Prefixed UTF-8"
        ))
        SettingGroup("ABOUT", listOf(
            "System" to "SwarTarang Offline Mesh",
            "Version" to "3.0 (Multilingual)",
            "Engine" to "IndicConformer INT8 ONNX"
        ))
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingGroup(title: String, rows: List<Pair<String, String>>) {
    Text(title, color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
    GlassPanel {
        rows.forEachIndexed { i, row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row.first, color = SoftWhite, fontSize = 15.sp)
                Text(row.second, color = Muted, fontSize = 14.sp)
            }
            if (i != rows.lastIndex) HorizontalDivider(color = Color.White.copy(.06f))
        }
    }
}

@Composable
private fun VoiceOrb(
    size: androidx.compose.ui.unit.Dp,
    active: Boolean,
    isWalkieTalkie: Boolean = true,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val currentActive by rememberUpdatedState(active)
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnStop by rememberUpdatedState(onStop)

    val infinite = rememberInfiniteTransition(label = "orb")
    val pulse by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 1.10f else 1.03f,
        animationSpec = infiniteRepeatable(tween(if (active) 800 else 2000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    Box(
        Modifier
            .size(size)
            .scale(pulse)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(
                        Color(0xFFFDC54C), // Sunny warm butter yellow
                        Color(0xFFF59E0B), // Golden amber
                        Color(0xFFBA5D3F)  // Grounded warm rust
                    )
                )
            )
            .border(1.5.dp, Color(0xFFE28A3B).copy(alpha = 0.45f), CircleShape)
            .pointerInput(isWalkieTalkie) {
                if (isWalkieTalkie) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        try {
                            currentOnStart()
                            waitForUpOrCancellation()
                        } finally {
                            currentOnStop()
                        }
                    }
                } else {
                    detectTapGestures(onTap = {
                        if (currentActive) currentOnStop() else currentOnStart()
                    })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (active) {
            Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                drawCircle(Color(0xFFFFFBEB).copy(alpha = 0.55f), style = Stroke(4.dp.toPx()))
            }
        }
        Icon(
            if (active) (if (isWalkieTalkie) Icons.Default.Mic else Icons.Default.PhoneInTalk) 
            else (if (isWalkieTalkie) Icons.Default.MicNone else Icons.Default.Phone),
            null,
            tint = Color(0xFF221712), // Deep dark espresso mic icon as in reference image!
            modifier = Modifier.size(size / 3)
        )
    }
}

@Composable
private fun ConnectionVisual(connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        Node("PHONE A", Rust)
        Box(Modifier.width(90.dp).height(2.dp).background(if (connected) Success else Rust.copy(.5f)))
        Node("PHONE B", WarmAmber)
    }
}

@Composable
private fun Node(label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(58.dp).clip(CircleShape).background(color.copy(.12f)).border(1.dp, color.copy(.6f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Smartphone, null, tint = color, modifier = Modifier.size(26.dp))
        }
        Text(label, color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(ButterYellow))
        Spacer(Modifier.width(8.dp))
        Text("SWARTARANG", color = TextDark, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    }
}

@Composable
private fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    onAction: (() -> Unit)? = null,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Settings,
    status: Boolean = false,
    showLogo: Boolean = true
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = TextDark,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            if (showLogo) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "App Logo",
                    modifier = Modifier.size(30.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(title, color = TextDark, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            if (status) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(7.dp).clip(CircleShape).background(Success))
            }
        }
        if (onAction != null) {
            IconButton(onClick = onAction, modifier = Modifier.size(36.dp)) {
                Icon(actionIcon, contentDescription = "Action", tint = TextMuted, modifier = Modifier.size(22.dp))
            }
        } else {
            Spacer(Modifier.size(36.dp))
        }
    }
}

@Composable
private fun GlassPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(SurfaceWhite)
            .border(1.dp, Border, RoundedCornerShape(22.dp))
            .padding(18.dp),
        content = content
    )
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick, 
        modifier = modifier, 
        shape = RoundedCornerShape(16.dp), 
        colors = ButtonDefaults.buttonColors(containerColor = Rust), 
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 15.dp)
    ) {
        Text(text, color = PureWhite, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick, 
        modifier = modifier, 
        shape = RoundedCornerShape(16.dp), 
        border = BorderStroke(1.dp, Border), 
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 15.dp)
    ) {
        Text(text, color = TextDark, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun RoleCard(icon: String, title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SurfaceWhite)
            .border(1.5.dp, color.copy(.7f), RoundedCornerShape(24.dp))
            .clickable { onClick() }
            .padding(24.dp), 
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 34.sp)
        Spacer(Modifier.height(10.dp))
        Text(title, color = TextDark, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text(subtitle, color = TextMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
private fun RecentCard(message: VoiceMessage) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(SurfaceButter)
            .border(1.dp, BorderButter, RoundedCornerShape(22.dp))
            .padding(18.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("RECENT SPEECH", color = TextAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text(message.time, color = TextMuted, fontSize = 11.sp)
        }
        Text(message.text, color = TextDark, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("🎙  ${message.duration} • Sent", color = TextMuted, fontSize = 12.sp)
            Text(Language.fromCode(message.sourceLanguage).displayName.uppercase(), color = TextAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MessageCard(
    message: VoiceMessage,
    playbackState: PlaybackState = PlaybackState.IDLE,
    onPlay: () -> Unit = {},
    onPause: () -> Unit = {},
    onStop: () -> Unit = {},
    onReplay: () -> Unit = {}
) {
    var showOriginal by remember(message.id) { mutableStateOf(false) }
    val srcLang = Language.fromCode(message.sourceLanguage)
    val hasTranslation = message.originalText != null && message.originalText != message.text

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.sent) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 310.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (message.sent) SurfaceWhite else SurfaceButter)
                .border(1.dp, if (message.sent) Border else BorderButter, RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (message.sent) "YOU (${srcLang.nativeName})" else "SENDER (${srcLang.nativeName})",
                        color = if (message.sent) Rust else TextAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    if (message.priority == MessagePriority.ALERT) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFE53935))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text("🚨 ALERT", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }

                if (hasTranslation) {
                    val tgtLang = Language.fromCode(message.translatedLanguage ?: "mr")
                    Text(
                        "${srcLang.code.uppercase()} ➔ ${tgtLang.code.uppercase()}",
                        color = Rust,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(message.text, color = TextDark, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            
            if (hasTranslation) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (showOriginal) "▲ Hide Original" else "▼ View Original (${srcLang.displayName})",
                    color = Rust,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { showOriginal = !showOriginal }
                )
                if (showOriginal) {
                    Text(
                        "\"${message.originalText}\"",
                        color = TextMuted,
                        fontSize = 14.sp,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Voice-Note Playback Controls on Received Messages
            if (!message.sent) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = BorderButter.copy(alpha = 0.5f), thickness = 1.dp)
                Spacer(Modifier.height(8.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status Badge
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (playbackState) {
                            PlaybackState.PLAYING -> {
                                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Rust, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Playing...", color = Rust, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            PlaybackState.QUEUED -> {
                                Icon(Icons.Default.Schedule, contentDescription = null, tint = WarmAmber, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Queued", color = WarmAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            PlaybackState.PAUSED -> {
                                Icon(Icons.Default.Pause, contentDescription = null, tint = WarmAmber, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Paused", color = WarmAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            PlaybackState.COMPLETED -> {
                                Icon(Icons.Default.DoneAll, contentDescription = null, tint = Success, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Played", color = Success, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            PlaybackState.FAILED -> {
                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = AlertRed, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("TTS Error", color = AlertRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            PlaybackState.IDLE -> {
                                Icon(Icons.Default.Headphones, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Voice Note", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Mini Playback Action Buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        when (playbackState) {
                            PlaybackState.PLAYING -> {
                                IconButton(
                                    onClick = onPause,
                                    modifier = Modifier.size(28.dp).clip(CircleShape).background(SurfaceWhite).border(1.dp, Border, CircleShape)
                                ) {
                                    Icon(Icons.Default.Pause, contentDescription = "Pause", tint = TextDark, modifier = Modifier.size(14.dp))
                                }
                                IconButton(
                                    onClick = onStop,
                                    modifier = Modifier.size(28.dp).clip(CircleShape).background(AlertRed.copy(0.12f)).border(1.dp, AlertRed.copy(0.3f), CircleShape)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = "Stop", tint = AlertRed, modifier = Modifier.size(14.dp))
                                }
                            }
                            PlaybackState.PAUSED -> {
                                IconButton(
                                    onClick = onPlay,
                                    modifier = Modifier.size(28.dp).clip(CircleShape).background(Rust)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = PureWhite, modifier = Modifier.size(14.dp))
                                }
                                IconButton(
                                    onClick = onReplay,
                                    modifier = Modifier.size(28.dp).clip(CircleShape).background(SurfaceWhite).border(1.dp, Border, CircleShape)
                                ) {
                                    Icon(Icons.Default.Replay, contentDescription = "Replay", tint = TextDark, modifier = Modifier.size(14.dp))
                                }
                            }
                            else -> {
                                IconButton(
                                    onClick = onPlay,
                                    modifier = Modifier.size(28.dp).clip(CircleShape).background(Rust)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = PureWhite, modifier = Modifier.size(14.dp))
                                }
                                IconButton(
                                    onClick = onReplay,
                                    modifier = Modifier.size(28.dp).clip(CircleShape).background(SurfaceWhite).border(1.dp, Border, CircleShape)
                                ) {
                                    Icon(Icons.Default.Replay, contentDescription = "Replay", tint = TextDark, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (message.sent) "✓ Sent" else "✓ Received", color = if (message.sent) Success else TextAmber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(message.time, color = TextMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun BottomNav(selected: Screen, onNavigate: (Screen) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(SurfaceWhite)
            .border(1.dp, Border, RoundedCornerShape(22.dp))
            .padding(6.dp), 
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        listOf(
            Screen.Home to (Icons.Default.Home to "HOME"),
            Screen.Chat to (Icons.Default.ChatBubbleOutline to "CHAT"),
            Screen.Device to (Icons.Default.Devices to "DEVICE")
        ).forEach { (screen, item) ->
            val isSelected = selected == screen
            Column(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isSelected) SurfaceButter else Color.Transparent)
                    .clickable { onNavigate(screen) }
                    .padding(horizontal = 22.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(item.first, null, tint = if (isSelected) Rust else TextMuted, modifier = Modifier.size(19.dp))
                Spacer(Modifier.height(2.dp))
                Text(item.second, color = if (isSelected) Rust else TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }
}
