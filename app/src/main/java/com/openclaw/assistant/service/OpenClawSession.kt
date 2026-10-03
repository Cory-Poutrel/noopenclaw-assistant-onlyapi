package com.openclaw.assistant.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.service.voice.VoiceInteractionSession
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.LayoutInflater
import android.view.WindowManager
import com.openclaw.assistant.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.openclaw.assistant.R
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.ManualDictationBuffer
import com.openclaw.assistant.speech.TTSManager
import com.openclaw.assistant.speech.TTSState
import com.openclaw.assistant.speech.SpeechResult
import com.openclaw.assistant.speech.TTSUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import com.openclaw.assistant.ui.theme.VoiceOverlayTheme

/**
 * Voice Interaction Session
 * Handles actual voice interaction
 */
class OpenClawSession(context: Context) : VoiceInteractionSession(context), 
    androidx.lifecycle.LifecycleOwner,
    androidx.savedstate.SavedStateRegistryOwner,
    androidx.lifecycle.ViewModelStoreOwner {

    companion object {
        private const val TAG = "OpenClawSession"
        private const val INITIAL_FILLER_DELAY_MS = 750L
        private const val INTERRUPT_LISTEN_DELAY_MS = 350L
    }

    private val settings = SettingsRepository.getInstance(context)
    private val apiClient = OpenClawClient()
    private lateinit var speechManager: SpeechRecognizerManager
    private lateinit var ttsManager: TTSManager
    
    // Repository
    private val chatRepository = com.openclaw.assistant.data.repository.ChatRepository.getInstance(context)
    private var currentSessionId: String? = null
    
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var initialFillerPhraseJob: Job? = null
    private var auxiliarySpeechJob: Job? = null
    @Volatile private var ignoreNextTtsStop = false
    private val interruptReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.openclaw.assistant.ACTION_INTERRUPT_TTS") return
            if (currentState.value != AssistantState.SPEAKING &&
                currentState.value != AssistantState.PREPARING_SPEECH) return
            Log.d(TAG, "Barge-in interrupt received in OpenClawSession")
            interruptAndListen()
        }
    }

    // UI State
    private var currentState = mutableStateOf(AssistantState.IDLE)
    private var displayText = mutableStateOf("")
    private var userQuery = mutableStateOf("") // User's spoken text
    private var partialText = mutableStateOf("")
    private val conversationMessages = mutableStateListOf<ConversationMessage>()
    private var errorMessage = mutableStateOf<String?>(null)
    private var audioLevel = mutableStateOf(0f) // Audio level for visualization
    private val manualDictationBuffer = ManualDictationBuffer()
    private var manualRecognitionActive = mutableStateOf(false)
    private var manualDictationCancelled = mutableStateOf(false)
    private var manualSubmitRequested = false
    private var manualSubmissionInProgress = false
    private val assistantTouchableRegion = android.graphics.Rect()
    private var assistantContentView: View? = null

    private val localizedUiContext: Context by lazy {
        val languageTag = settings.appLanguage.trim()
        if (languageTag.isBlank()) {
            context
        } else {
            val configuration = android.content.res.Configuration(context.resources.configuration)
            configuration.setLocale(java.util.Locale.forLanguageTag(languageTag))
            context.createConfigurationContext(configuration)
        }
    }

    // WakeLock to keep CPU alive during voice conversation when screen is off
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        Log.e(TAG, "Session onCreate start")
        super.onCreate()
        
        // Initialize lifecycle and saved state here (once per session lifetime)
        try {
            savedStateRegistryController.performAttach()
        } catch (e: Exception) {
            Log.w(TAG, "SavedStateRegistry already attached?", e)
        }
        
        try {
            savedStateRegistryController.performRestore(null)
        } catch (e: Exception) {
            Log.w(TAG, "SavedStateRegistry already restored?", e)
        }

        try {
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        } catch (e: Exception) {
             Log.w(TAG, "Lifecycle ON_CREATE failed", e)
        }

        speechManager = SpeechRecognizerManager(context)
        ttsManager = TTSManager(context)
        val initialized = ttsManager.initializeCurrentProvider()
        Log.e(TAG, "Session TTS: initialized=$initialized ready=${ttsManager.isReady()} error=${ttsManager.getErrorMessage()}")
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            interruptReceiver,
            android.content.IntentFilter("com.openclaw.assistant.ACTION_INTERRUPT_TTS"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val savedStateRegistryController = androidx.savedstate.SavedStateRegistryController.create(this)
    
    // Audio Cue
    private val toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
    private val toneGeneratorReleased = AtomicBoolean(false)

    private fun playTone(tone: Int, durationMs: Int = -1) {
        if (toneGeneratorReleased.get()) return
        try {
            if (durationMs == -1) toneGenerator.startTone(tone)
            else toneGenerator.startTone(tone, durationMs)
        } catch (e: RuntimeException) {
            Log.w(TAG, "ToneGenerator already released", e)
        }
    }

    // AudioFocus management
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    // Session foreground service keeps process alive during conversation (prevents MIUI/HyperOS freeze)

    override val lifecycle: androidx.lifecycle.Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreateContentView(): View {
        Log.e(TAG, "Session onCreateContentView")
        val composeView = ComposeView(localizedUiContext).apply {
            Log.e(TAG, "Initializing ComposeView with owners")
            // Set ViewTree owners using extensions
            try {
                setViewTreeLifecycleOwner(this@OpenClawSession)
                setViewTreeViewModelStoreOwner(this@OpenClawSession)
                setViewTreeSavedStateRegistryOwner(this@OpenClawSession)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set ViewTree owners", e)
            }
            
            setContent {
                val assistantState = currentState.value
                LaunchedEffect(assistantState) {
                    setScreenAwake(assistantState.keepsScreenAwake())
                }
                VoiceOverlayTheme(darkTheme = settings.assistantOverlayDarkTheme) {
                    AssistantUI(
                        state = assistantState,
                        messages = conversationMessages,
                        darkTheme = settings.assistantOverlayDarkTheme,
                        displayText = displayText.value,
                        userQuery = userQuery.value,
                        partialText = partialText.value,
                        errorMessage = errorMessage.value,
                        audioLevel = audioLevel.value,
                        onClose = {
                            isUserDismissed = true
                            finish()
                        },
                        onRetry = { startListening() },
                        onInterrupt = { interruptAndListen() },
                        manualDictationEnabled = settings.dictationMode == SettingsRepository.DICTATION_MODE_MANUAL,
                        manualDictationListening = manualRecognitionActive.value,
                        manualDictationCancelled = manualDictationCancelled.value,
                        canSubmitManualDictation = manualDictationBuffer.text().isNotBlank() && !manualSubmissionInProgress,
                        onSubmitManualDictation = { requestManualSubmission() },
                        onCancelManualDictation = { cancelManualDictation() },
                        onPauseManualDictation = { pauseManualDictation() },
                        onResumeManualDictation = { resumeManualDictation() },
                        onPanelBoundsChanged = { left, top, right, bottom ->
                            val changed = assistantTouchableRegion.left != left ||
                                assistantTouchableRegion.top != top ||
                                assistantTouchableRegion.right != right ||
                                assistantTouchableRegion.bottom != bottom
                            if (changed) {
                                assistantTouchableRegion.set(left, top, right, bottom)
                                assistantContentView?.requestLayout()
                            }
                        }
                    )
                }
            }
        }
        assistantContentView = composeView
        return composeView
    }

    override fun onComputeInsets(outInsets: VoiceInteractionSession.Insets) {
        super.onComputeInsets(outInsets)
        if (assistantTouchableRegion.isEmpty) {
            outInsets.touchableInsets = VoiceInteractionSession.Insets.TOUCHABLE_INSETS_FRAME
        } else {
            outInsets.touchableInsets = VoiceInteractionSession.Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.set(assistantTouchableRegion)
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        // Recreate scope if it was cancelled by a previous onHide()
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }

        // Ensure any existing SpeechRecognizerManager is cleaned up before creating a new one
        if (this::speechManager.isInitialized) {
            try {
                speechManager.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to destroy existing SpeechRecognizerManager before recreation", e)
            }
        }
        speechManager = SpeechRecognizerManager(context)

        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)

        // Start foreground service to keep process alive during conversation (screen off)
        SessionForegroundService.start(context)

        Log.d(TAG, "Session shown with flags: $showFlags")

        // PAUSE Hotword Service to prevent microphone conflict
        sendPauseBroadcast()
        
        // SESSION MANAGEMENT
        if (settings.wakewordConnectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY) {
            // Gateway mode: manage session on the gateway side, not in local DB
            val nodeRuntime = (context.applicationContext as OpenClawApplication).nodeRuntime
            if (!settings.resumeLatestSession) {
                // Start a fresh gateway session with a human-readable label
                val newKey = java.util.UUID.randomUUID().toString()
                val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                val label = String.format(context.getString(R.string.default_session_title_format), timeStr)
                nodeRuntime.switchChatSession(newKey)
                scope.launch { nodeRuntime.patchChatSession(newKey, label) }
            }
            // resumeLatestSession ON → keep the current active gateway session as-is
        } else {
            scope.launch {
                try {
                    val latestSession = if (settings.resumeLatestSession) chatRepository.getLatestSession() else null
                    if (latestSession != null) {
                        currentSessionId = latestSession.id
                        Log.d(TAG, "Resuming latest session: $currentSessionId")
                    } else {
                        currentSessionId = chatRepository.createSession(title = String.format(context.getString(R.string.default_session_title_format), java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())))
                        Log.d(TAG, "Created new session: $currentSessionId")
                    }

                    // Store this ID in settings so ChatActivity and API calls use it
                    currentSessionId?.let { settings.sessionId = it }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle session", e)
                    if (BuildConfig.FIREBASE_ENABLED) {
                        FirebaseCrashlytics.getInstance().recordException(e)
                    }
                }
            }
        }
        
        // Check settings
        if (!settings.isConfigured()) {
            currentState.value = AssistantState.ERROR
            errorMessage.value = context.getString(R.string.error_config_required)
            displayText.value = context.getString(R.string.config_required)
            return
        }

        // For Gateway mode, fail fast if the gateway is not healthy
        if (settings.wakewordConnectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY) {
            val nodeRuntime = (context.applicationContext as OpenClawApplication).nodeRuntime
            if (!nodeRuntime.chatHealthOk.value) {
                currentState.value = AssistantState.ERROR
                errorMessage.value = context.getString(R.string.error_gateway_not_connected)
                displayText.value = context.getString(R.string.config_required)
                return
            }
        }

        // Start speech recognition
        startListening()
    }
    
    override fun onHide() {
        super.onHide()

        // ユーザーが明示的に Close ボタンを押した場合は、音声状態に関わらず必ずクリーンアップ
        if (isUserDismissed) {
            isUserDismissed = false
            cleanupSession()
            return
        }

        // If a voice session is active (listening, thinking, or speaking),
        // keep resources alive so conversation continues with screen off.
        val state = currentState.value
        val isVoiceActive = state == AssistantState.LISTENING ||
                state == AssistantState.THINKING ||
                state == AssistantState.SPEAKING ||
                state == AssistantState.PREPARING_SPEECH ||
                state == AssistantState.PROCESSING
        
        if (isVoiceActive) {
            Log.d(TAG, "onHide: voice session active ($state), keeping resources alive")
            // Keep scope, speechManager, ttsManager alive
            // SessionForegroundService is already running
            return
        }

        cleanupSession()
    }

    private fun cleanupSession() {
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)

        // Clean up audio resources
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopThinkingSound()
        stopAuxiliarySpeech()
        abandonAudioFocus()
        SessionForegroundService.stop(context)
        scope.cancel()
        speechManager.destroy()
        ttsManager.stop()
        setScreenAwake(false)
        releaseWakeLock()

        // Resume Hotword
        sendResumeBroadcast()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopThinkingSound()
        stopAuxiliarySpeech()
        
        // Resume Hotword (safety)
        sendResumeBroadcast()
        try {
            context.unregisterReceiver(interruptReceiver)
        } catch (_: Exception) {
        }

        SessionForegroundService.stop(context)
        ttsManager.shutdown()
        toneGeneratorReleased.set(true)
        toneGenerator.release()
        setScreenAwake(false)
        releaseWakeLock()
    }

    private fun sendPauseBroadcast() {
        val intent = Intent("com.openclaw.assistant.ACTION_PAUSE_HOTWORD")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
    
    private fun sendResumeBroadcast() {
        val intent = Intent("com.openclaw.assistant.ACTION_RESUME_HOTWORD")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }

    // Must implement ViewModelStoreOwner for Compose
    override val viewModelStore: androidx.lifecycle.ViewModelStore = androidx.lifecycle.ViewModelStore()

    private var listeningJob: Job? = null
    private var speakingJob: Job? = null
    private var isUserDismissed = false

    private fun startListening(initialDelayMs: Long = 50L, preserveManualBuffer: Boolean = false) {
        if (settings.dictationMode == SettingsRepository.DICTATION_MODE_MANUAL) {
            startManualListening(initialDelayMs, preserveManualBuffer)
        } else {
            startAutomaticListening(initialDelayMs)
        }
    }

    private fun startAutomaticListening(initialDelayMs: Long) {
        Log.d(TAG, "startAutomaticListening() called, currentState=${currentState.value}, listeningJob=${listeningJob}, speakingJob=${speakingJob}")
        listeningJob?.cancel()
        acquireWakeLock()
        sendPauseBroadcast()

        currentState.value = AssistantState.PROCESSING
        displayText.value = ""
        userQuery.value = ""
        partialText.value = ""
        errorMessage.value = null
        audioLevel.value = 0f

        scope.launch {
            delay(2000L)
            if (currentState.value == AssistantState.PROCESSING) {
                Log.w(TAG, "SpeechResult.Ready timeout — forcing LISTENING state")
                currentState.value = AssistantState.LISTENING
            }
        }

        listeningJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var hasActuallySpoken = false

            delay(initialDelayMs)

            while (isActive && !hasActuallySpoken) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest = android.media.AudioFocusRequest.Builder(
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                    ).build()
                    audioManager.requestAudioFocus(audioFocusRequest!!)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(
                        null,
                        android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    )
                }

                val listenResult = withTimeoutOrNull(30_000L) {
                    speechManager.startListening(
                        settings.speechLanguage.ifEmpty { null },
                        settings.speechSilenceTimeout
                    ).collectLatest { result ->
                        when (result) {
                            is SpeechResult.Ready -> {
                                Log.d(TAG, "SpeechResult.Ready received, transitioning to LISTENING")
                                currentState.value = AssistantState.LISTENING
                                playTone(android.media.ToneGenerator.TONE_PROP_BEEP)
                            }
                            is SpeechResult.Processing -> Unit
                            is SpeechResult.Listening -> {
                                if (currentState.value != AssistantState.LISTENING) {
                                    currentState.value = AssistantState.LISTENING
                                }
                            }
                            is SpeechResult.RmsChanged -> audioLevel.value = result.rmsdB
                            is SpeechResult.PartialResult -> {
                                partialText.value = result.text
                                if (currentState.value != AssistantState.LISTENING) {
                                    currentState.value = AssistantState.LISTENING
                                }
                            }
                            is SpeechResult.Result -> {
                                Log.d(TAG, "SpeechResult.Result received: text='${result.text}'")
                                hasActuallySpoken = true
                                userQuery.value = result.text
                                sendToOpenClaw(result.text)
                            }
                            is SpeechResult.Error -> {
                                val elapsed = System.currentTimeMillis() - startTime
                                val isTimeout = result.code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                    result.code == SpeechRecognizer.ERROR_NO_MATCH

                                if (isTimeout && settings.continuousMode && elapsed < 10000) {
                                    Log.d(TAG, "Speech timeout within 10s window ($elapsed ms), retrying...")
                                } else if (
                                    result.code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                                    result.code == SpeechRecognizer.ERROR_CLIENT
                                ) {
                                    speechManager.destroy()
                                    delay(500)
                                } else if (isTimeout) {
                                    val state = currentState.value
                                    if (state == AssistantState.THINKING || state == AssistantState.SPEAKING) {
                                        Log.d(TAG, "Speech timeout but AI is $state, not closing session")
                                        hasActuallySpoken = true
                                    } else {
                                        playTone(android.media.ToneGenerator.TONE_PROP_NACK, 100)
                                        finish()
                                    }
                                } else {
                                    playTone(android.media.ToneGenerator.TONE_PROP_NACK, 100)
                                    currentState.value = AssistantState.ERROR
                                    errorMessage.value = result.message
                                    hasActuallySpoken = true
                                }
                            }
                            else -> Unit
                        }
                    }
                }

                if (listenResult == null && !hasActuallySpoken) {
                    Log.w(TAG, "Speech recognition timed out (30s). Device may be locked.")
                    val state = currentState.value
                    if (state == AssistantState.THINKING || state == AssistantState.SPEAKING) {
                        Log.d(TAG, "Manual timeout but AI is $state, not closing session")
                        hasActuallySpoken = true
                    } else {
                        finish()
                        hasActuallySpoken = true
                    }
                }

                if (!hasActuallySpoken) {
                    delay(300)
                }
            }
        }
    }

    private fun startManualListening(initialDelayMs: Long, preserveManualBuffer: Boolean) {
        Log.d(TAG, "startListening() called, currentState=${currentState.value}, listeningJob=${listeningJob}, speakingJob=${speakingJob}")
        listeningJob?.cancel()
        acquireWakeLock()
        sendPauseBroadcast()

        val manualMode = true
        currentState.value = if (preserveManualBuffer) AssistantState.LISTENING else AssistantState.PROCESSING
        displayText.value = ""
        userQuery.value = ""
        if (!preserveManualBuffer) {
            partialText.value = ""
        }
        errorMessage.value = null
        audioLevel.value = 0f
        if (!preserveManualBuffer) {
            manualDictationBuffer.clear()
        }
        manualSubmitRequested = false
        manualSubmissionInProgress = false
        manualDictationCancelled.value = false

        manualRecognitionActive.value = manualMode

        // Fallback: if SpeechResult.Ready doesn't arrive within 2s, force LISTENING
        // so users don't see "Processing" indefinitely while the recognizer warms up
        scope.launch {
            delay(2000L)
            if (currentState.value == AssistantState.PROCESSING) {
                Log.w(TAG, "SpeechResult.Ready timeout — forcing LISTENING state")
                currentState.value = AssistantState.LISTENING
            }
        }

        listeningJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var hasActuallySpoken = false
            var recognitionPass = 0
            
            // Wait for resources to be released before reopening the mic
            delay(initialDelayMs)

            while (isActive && !hasActuallySpoken) {
                recognitionPass += 1
                // Request audio focus
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest = android.media.AudioFocusRequest.Builder(
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                    ).build()
                    audioManager.requestAudioFocus(audioFocusRequest!!)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(null,
                        android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }

                val collectSpeechResults: suspend () -> Unit = {
                    speechManager.startListening(
                        settings.speechLanguage.ifEmpty { null },
                        settings.speechSilenceTimeout,
                        segmentedSession = manualMode
                    ).collectLatest { result ->
                        when (result) {
                            is SpeechResult.Ready -> {
                                Log.d(TAG, "SpeechResult.Ready received, transitioning to LISTENING")
                                currentState.value = AssistantState.LISTENING
                                manualRecognitionActive.value = manualMode
                                if (!manualMode || recognitionPass == 1) {
                                    playTone(android.media.ToneGenerator.TONE_PROP_BEEP)
                                }
                            }
                            is SpeechResult.Processing -> {
                                // No sound here - thinking ACK sound will play when AI starts processing
                            }
                            is SpeechResult.Listening -> {
                                if (currentState.value != AssistantState.LISTENING) {
                                    currentState.value = AssistantState.LISTENING
                                }
                            }
                            is SpeechResult.RmsChanged -> {
                                audioLevel.value = result.rmsdB
                            }
                            is SpeechResult.PartialResult -> {
                                if (manualMode) {
                                    manualDictationBuffer.updatePartial(result.text)
                                    partialText.value = manualDictationBuffer.text()
                                } else {
                                    partialText.value = result.text
                                }
                                // Ensure state is listening if we get partial results
                                if (currentState.value != AssistantState.LISTENING) {
                                    currentState.value = AssistantState.LISTENING
                                }
                            }
                            is SpeechResult.Result -> {
                                Log.d(TAG, "SpeechResult.Result received: text='${result.text}'")
                                if (manualMode) {
                                    if (manualSubmitRequested) {
                                        hasActuallySpoken = true
                                        completeManualSubmission(result.text)
                                    } else {
                                        manualDictationBuffer.commitFinal(result.text)
                                        partialText.value = manualDictationBuffer.text()
                                        manualRecognitionActive.value = false
                                        currentState.value = AssistantState.IDLE
                                        hasActuallySpoken = true
                                    }
                                } else {
                                    hasActuallySpoken = true
                                    userQuery.value = result.text
                                    sendToOpenClaw(result.text)
                                }
                            }
                            is SpeechResult.SegmentResult -> {
                                if (manualMode) {
                                    Log.d(TAG, "Manual dictation segment received")
                                    manualDictationBuffer.commitFinal(result.text)
                                    partialText.value = manualDictationBuffer.text()
                                    currentState.value = AssistantState.LISTENING
                                }
                            }
                            is SpeechResult.EndOfSegmentedSession -> {
                                if (manualMode) {
                                    manualDictationBuffer.commitPartial()
                                    partialText.value = manualDictationBuffer.text()
                                    manualRecognitionActive.value = false
                                    currentState.value = AssistantState.IDLE
                                    hasActuallySpoken = true
                                }
                            }
                            is SpeechResult.Error -> {
                                val elapsed = System.currentTimeMillis() - startTime
                                val isTimeout = result.code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                              result.code == SpeechRecognizer.ERROR_NO_MATCH

                                if (manualMode && manualSubmitRequested) {
                                    hasActuallySpoken = true
                                    completeManualSubmission()
                                } else if (manualMode) {
                                    Log.d(TAG, "Manual dictation stopped; waiting for a microphone tap")
                                    manualDictationBuffer.commitPartial()
                                    partialText.value = manualDictationBuffer.text()
                                    manualRecognitionActive.value = false
                                    currentState.value = AssistantState.IDLE
                                    hasActuallySpoken = true
                                } else if (isTimeout && settings.continuousMode && elapsed < 10000) {
                                    Log.d(TAG, "Speech timeout within 10s window ($elapsed ms), retrying...")
                                } else if (
                                    result.code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                                    result.code == SpeechRecognizer.ERROR_CLIENT
                                ) {
                                    speechManager.destroy()
                                    delay(500)
                                } else if (isTimeout) {
                                    // Timeout - close session without error screen
                                    // But only if AI is not currently thinking or speaking
                                    val state = currentState.value
                                    if (state == AssistantState.THINKING || state == AssistantState.SPEAKING) {
                                        Log.d(TAG, "Speech timeout but AI is $state, not closing session")
                                        hasActuallySpoken = true // break the loop but don't finish
                                    } else {
                                        playTone(android.media.ToneGenerator.TONE_PROP_NACK, 100)
                                        finish() // Close the session
                                    }
                                } else {
                                    playTone(android.media.ToneGenerator.TONE_PROP_NACK, 100)
                                    currentState.value = AssistantState.ERROR
                                    errorMessage.value = result.message
                                    hasActuallySpoken = true
                                }
                            }
                            else -> {}
                        }
                    }
                }

                val listenResult = if (manualMode) {
                    collectSpeechResults()
                    Unit
                } else {
                    withTimeoutOrNull(30_000L) { collectSpeechResults() }
                }

                if (manualMode && !hasActuallySpoken) {
                    manualDictationBuffer.commitPartial()
                    partialText.value = manualDictationBuffer.text()
                    manualRecognitionActive.value = false
                    currentState.value = AssistantState.IDLE
                    hasActuallySpoken = true
                }

                if (listenResult == null && !hasActuallySpoken) {
                    Log.w(TAG, "Speech recognition timed out (30s). Device may be locked.")
                    // Manual timeout - only close session if not thinking/speaking
                    val state = currentState.value
                    if (state == AssistantState.THINKING || state == AssistantState.SPEAKING) {
                        Log.d(TAG, "Manual timeout but AI is $state, not closing session")
                        hasActuallySpoken = true // break the loop but don't finish
                    } else {
                        finish() // Close the session
                        hasActuallySpoken = true
                    }
                }
                
                if (!hasActuallySpoken) {
                    delay(300)
                }
            }
        }
    }

    private fun requestManualSubmission() {
        if (settings.dictationMode != SettingsRepository.DICTATION_MODE_MANUAL ||
            manualSubmissionInProgress ||
            manualDictationBuffer.text().isBlank()
        ) {
            return
        }

        manualSubmitRequested = true
        manualSubmissionInProgress = true
        currentState.value = AssistantState.PROCESSING
        if (manualRecognitionActive.value) {
            speechManager.stopListening()
        } else {
            completeManualSubmission()
            return
        }

        // Some recognizer implementations do not deliver a final result after
        // stopListening(). Submit the latest partial transcript as a fallback.
        scope.launch {
            delay(1500L)
            if (manualSubmitRequested) {
                completeManualSubmission()
            }
        }
    }

    private fun completeManualSubmission(finalSegment: String? = null) {
        if (!manualSubmitRequested) return

        finalSegment?.let(manualDictationBuffer::commitFinal)
        val message = manualDictationBuffer.text().trim()
        manualSubmitRequested = false
        manualSubmissionInProgress = false
        manualRecognitionActive.value = false

        if (message.isBlank()) {
            currentState.value = AssistantState.LISTENING
            return
        }

        listeningJob?.cancel()
        speechManager.destroy()
        abandonAudioFocus()
        manualDictationBuffer.clear()
        partialText.value = ""
        userQuery.value = message
        sendToOpenClaw(message)
    }

    private fun cancelManualDictation() {
        if (settings.dictationMode != SettingsRepository.DICTATION_MODE_MANUAL ||
            manualSubmissionInProgress
        ) {
            return
        }

        manualSubmitRequested = false
        manualDictationBuffer.clear()
        partialText.value = ""
        userQuery.value = ""
        listeningJob?.cancel()
        speechManager.destroy()
        abandonAudioFocus()
        manualRecognitionActive.value = false
        manualDictationCancelled.value = true
        currentState.value = AssistantState.IDLE
    }

    private fun pauseManualDictation() {
        if (settings.dictationMode != SettingsRepository.DICTATION_MODE_MANUAL ||
            manualSubmissionInProgress ||
            !manualRecognitionActive.value
        ) {
            return
        }

        manualSubmitRequested = false
        manualDictationBuffer.commitPartial()
        partialText.value = manualDictationBuffer.text()
        listeningJob?.cancel()
        speechManager.destroy()
        abandonAudioFocus()
        manualRecognitionActive.value = false
        manualDictationCancelled.value = false
        currentState.value = AssistantState.IDLE
    }

    private fun resumeManualDictation() {
        if (settings.dictationMode != SettingsRepository.DICTATION_MODE_MANUAL ||
            manualSubmissionInProgress ||
            manualRecognitionActive.value
        ) {
            return
        }

        startListening(preserveManualBuffer = true)
    }

    private var thinkingSoundJob: Job? = null

    private fun startThinkingSound() {
        thinkingSoundJob?.cancel()
        if (!settings.thinkingSoundEnabled) return
        thinkingSoundJob = scope.launch {
            delay(2000)
            while (isActive) {
                playTone(android.media.ToneGenerator.TONE_SUP_RINGTONE, 100)
                delay(3000)
            }
        }
    }

    private fun stopThinkingSound() {
        thinkingSoundJob?.cancel()
        thinkingSoundJob = null
    }

    private fun sendToOpenClaw(message: String) {
        Log.d(TAG, "sendToOpenClaw() called, transitioning to THINKING")
        val submittedMessage = message.trim()
        if (submittedMessage.isNotEmpty()) {
            conversationMessages.add(ConversationMessage(text = submittedMessage, isUser = true))
        }
        userQuery.value = ""
        partialText.value = ""
        currentState.value = AssistantState.THINKING
        playTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
        startThinkingSound()
        displayText.value = ""

        // 相槌フレーズの再生（LLMへのリクエストと並行して実行）
        if (settings.fillerPhrasesEnabled) {
            scheduleInitialFillerPhrase()
        }

        scope.launch {
            // Save user message to local DB only for HTTP mode
            if (settings.wakewordConnectionType != SettingsRepository.CONNECTION_TYPE_GATEWAY) {
                currentSessionId?.let { sessionId ->
                    chatRepository.addMessage(sessionId, message, isUser = true)
                }
            }

            if (settings.wakewordConnectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY) {
                sendViaGateway(message)
            } else {
                sendViaHttp(message)
            }
        }
    }

    private var waitPhraseJob: Job? = null

    private fun scheduleInitialFillerPhrase() {
        cancelInitialFillerPhrase()
        if (!settings.fillerPhrasesEnabled || !settings.ttsEnabled) return
        initialFillerPhraseJob = scope.launch {
            delay(INITIAL_FILLER_DELAY_MS)
            if (currentState.value == AssistantState.THINKING && isActive) {
                playFillerPhrase()
            }
        }
    }

    private fun cancelInitialFillerPhrase() {
        initialFillerPhraseJob?.cancel()
        initialFillerPhraseJob = null
    }

    private fun startWaitPhraseTimer() {
        waitPhraseJob?.cancel()
        if (!settings.fillerPhrasesEnabled || !settings.ttsEnabled) return
        waitPhraseJob = scope.launch {
            delay(5000) // 5秒待機
            if (currentState.value == AssistantState.THINKING && isActive) {
                Log.d(TAG, "Wait phrase timer triggered (> 5s). Playing wait phrase.")
                playWaitPhrase()
            }
        }
    }

    private fun cancelWaitPhraseTimer() {
        waitPhraseJob?.cancel()
        waitPhraseJob = null
    }

    private fun playFillerPhrase() {
        val phrase = context.getString(R.string.filler_ok)
        
        stopAuxiliarySpeech()
        var playbackJob: Job? = null
        playbackJob = scope.launch {
            try {
                // 相槌は progress 監視せずに即座に発話だけさせる
                ttsManager.speakWithProgress(phrase).collect {} 
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play filler phrase", e)
            } finally {
                if (auxiliarySpeechJob === playbackJob) {
                    auxiliarySpeechJob = null
                }
            }
        }
        auxiliarySpeechJob = playbackJob
    }

    private fun playWaitPhrase() {
        val waitPhrases = listOf(
            context.getString(R.string.wait_phrase_let_me_think),
            context.getString(R.string.wait_phrase_one_moment),
            context.getString(R.string.wait_phrase_checking)
        )
        val phrase = waitPhrases.random()
        
        stopAuxiliarySpeech()
        var playbackJob: Job? = null
        playbackJob = scope.launch {
            try {
                ttsManager.speakWithProgress(phrase).collect {}
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play wait phrase", e)
            } finally {
                if (auxiliarySpeechJob === playbackJob) {
                    auxiliarySpeechJob = null
                }
            }
        }
        auxiliarySpeechJob = playbackJob
    }

    private suspend fun sendViaGateway(message: String) {
        val nodeRuntime = (context.applicationContext as OpenClawApplication).nodeRuntime
        if (!nodeRuntime.chatHealthOk.value) {
            cancelInitialFillerPhrase()
            stopThinkingSound()
            currentState.value = AssistantState.ERROR
            errorMessage.value = context.getString(R.string.error_gateway_not_connected)
            return
        }

        try {
            val assistantCountBefore = nodeRuntime.chatMessages.value.count { it.role == "assistant" }

            startWaitPhraseTimer() // 待ちフレーズのタイマー開始

            nodeRuntime.sendChat(
                message = message,
                thinking = "low",
                attachments = emptyList()
            )

            // Wait for a new complete assistant response (timeout 60s).
            // chatMessages only adds a message when the full response is committed,
            // so watching it avoids both the pendingRunCount==0 early-fire issue
            // and streaming partial-text races.
            val responseText = withTimeoutOrNull(60_000L) {
                nodeRuntime.chatMessages
                    .first { messages -> messages.count { it.role == "assistant" } > assistantCountBefore }
                    .lastOrNull { it.role == "assistant" }
                    ?.content?.firstOrNull { it.type == "text" }?.text
            }

            cancelWaitPhraseTimer()

            if (responseText != null) {
                conversationMessages.add(ConversationMessage(text = responseText, isUser = false))
                handleResponseReceived(responseText)
            } else {
                cancelInitialFillerPhrase()
                stopThinkingSound()
                currentState.value = AssistantState.ERROR
                errorMessage.value = context.getString(R.string.error_no_response)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gateway error", e)
            cancelInitialFillerPhrase()
            cancelWaitPhraseTimer()
            stopThinkingSound()
            currentState.value = AssistantState.ERROR
            errorMessage.value = e.message ?: context.getString(R.string.error_network)
        }
    }

    private suspend fun sendViaHttp(message: String) {
        val agentId = settings.defaultAgentId.takeIf { it.isNotBlank() && it != "main" }
        
        startWaitPhraseTimer()
        
        val result = apiClient.sendMessage(
            httpUrl = settings.getChatCompletionsUrl(),
            message = message,
            sessionId = settings.sessionId,
            authToken = settings.authToken.takeIf { it.isNotBlank() },
            agentId = agentId
        )

        cancelWaitPhraseTimer()

        result.fold(
            onSuccess = { response ->
                val responseText = response.getResponseText()
                if (responseText != null) {
                    conversationMessages.add(ConversationMessage(text = responseText, isUser = false))
                    handleResponseReceived(responseText)
                } else if (response.error != null) {
                    cancelInitialFillerPhrase()
                    stopThinkingSound()
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = response.error
                } else {
                    cancelInitialFillerPhrase()
                    stopThinkingSound()
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = context.getString(R.string.error_no_response)
                }
            },
            onFailure = { error ->
                Log.e(TAG, "API error", error)
                cancelInitialFillerPhrase()
                stopThinkingSound()
                currentState.value = AssistantState.ERROR
                errorMessage.value = error.message ?: context.getString(R.string.error_network)
            }
        )
    }

    private suspend fun handleResponseReceived(responseText: String) {
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopAuxiliarySpeech()

        // Save AI response to local DB only for HTTP mode
        if (settings.wakewordConnectionType != SettingsRepository.CONNECTION_TYPE_GATEWAY) {
            currentSessionId?.let { sessionId ->
                chatRepository.addMessage(sessionId, responseText, isUser = false)
            }
        }

        if (settings.ttsEnabled) {
            // Thinking sound continues until actual audio playback starts
            speakResponse(responseText)
        } else if (settings.continuousMode) {
            stopThinkingSound()
            delay(500)
            startListening()
        } else {
            stopThinkingSound()
            // TTS disabled & continuous conversation OFF: Return to IDLE
            currentState.value = AssistantState.IDLE
            SessionForegroundService.stop(context)
        }
    }

    private fun interruptAndListen() {
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopThinkingSound()
        stopAuxiliarySpeech()
        listeningJob?.cancel()
        sendPauseBroadcast()
        ignoreNextTtsStop = true
        ttsManager.stop()
        speakingJob?.cancel()
        speakingJob = null
        speechManager.destroy()
        abandonAudioFocus()
        currentState.value = AssistantState.PROCESSING
        partialText.value = ""
        errorMessage.value = null
        scope.launch {
            delay(INTERRUPT_LISTEN_DELAY_MS)
            startListening()
        }
    }

    private fun speakResponse(text: String) {
        Log.d(TAG, "speakResponse() called, text length=${text.length}")
        // Thinking sound continues until TTSState.Speaking is received
        currentState.value = AssistantState.PREPARING_SPEECH
        val cleanText = TTSUtils.stripMarkdownForSpeech(text)

        speakingJob = scope.launch {
            ignoreNextTtsStop = false
            try {
                val maxLen = minOf(TTSUtils.getMaxInputLength(null), 1000)
                val chunks = TTSUtils.splitTextForTTS(cleanText, maxLen)
                var success = chunks.isNotEmpty()
                for (chunk in chunks) {
                    var chunkSuccess = false
                    ttsManager.speakWithProgress(chunk).collect { state ->
                        when (state) {
                            is TTSState.Preparing -> {
                                Log.d(TAG, "TTS Preparing")
                                // Keep PREPARING_SPEECH state
                            }
                            is TTSState.Speaking -> {
                                Log.d(TAG, "TTS Speaking")
                                stopThinkingSound()
                                currentState.value = AssistantState.SPEAKING
                                // Barge-inが有効な場合、読み上げ開始時にHotwordServiceを再開する
                                if (settings.ttsBargeInEnabled) {
                                    sendResumeBroadcast()
                                }
                            }
                            is TTSState.Done -> {
                                Log.d(TAG, "TTS Done")
                                chunkSuccess = true
                            }
                            is TTSState.Error -> {
                                if (ignoreNextTtsStop) {
                                    Log.d(TAG, "Ignoring TTS stop during controlled interruption")
                                    return@collect
                                }
                                Log.e(TAG, "TTS Error: ${state.message}")
                                chunkSuccess = false
                            }
                        }
                    }
                    if (!chunkSuccess) {
                        success = false
                        break
                    }
                }

                // abandonAudioFocus() : 連続対話やBarge-in対応のため、ここでは即座にfocusを捨てない運用にするか、
                // 次のアクション(マイクの起動等)まで保持しておく選択もある。
                // 既存の挙動を尊重し一旦残す
                abandonAudioFocus()

                // 読み上げ終了時にBarge-inのために再開していたHotwordServiceを再度一時停止させる
                // (この後すぐにlisteningに入る場合はそちらでpauseされるが念のため)
                if (settings.ttsBargeInEnabled && !settings.continuousMode) {
                    sendPauseBroadcast()
                }

                if (ignoreNextTtsStop) {
                    return@launch
                }

                if (success) {
                    // After speech completion, if continuous conversation mode is enabled, start listening again
                    if (settings.continuousMode) {
                        Log.d(TAG, "TTS complete, continuous mode ON. Starting 2nd rally startListening() in 500ms")
                        delay(500)
                        startListening()
                    } else {
                        // If continuous conversation is OFF, end the session
                        currentState.value = AssistantState.IDLE
                        releaseWakeLock()
                        SessionForegroundService.stop(context)
                    }
                } else {
                    currentState.value = AssistantState.ERROR
                    errorMessage.value = context.getString(R.string.error_speech_general)
                }
            } catch (e: CancellationException) {
                if (!ignoreNextTtsStop) {
                    throw e
                }
            } catch (e: Exception) {
                if (ignoreNextTtsStop) {
                    return@launch
                }
                Log.e(TAG, "TTS speak error", e)
                abandonAudioFocus()
                releaseWakeLock()
                currentState.value = AssistantState.ERROR
                errorMessage.value = context.getString(R.string.error_speech_general)
            }
        }
    }

    private fun stopAuxiliarySpeech() {
        val hadActiveAuxSpeech = auxiliarySpeechJob?.isActive == true
        auxiliarySpeechJob?.cancel()
        auxiliarySpeechJob = null
        if (hadActiveAuxSpeech) {
            ttsManager.stop()
        }
    }



    private fun abandonAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioFocusRequest = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OpenClawAssistant::SessionWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 min max to prevent leak
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun setScreenAwake(enabled: Boolean) {
        assistantContentView?.keepScreenOn = enabled
        window?.window?.let { sessionWindow ->
            if (enabled) {
                sessionWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                sessionWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        Log.d(TAG, "Keep screen awake: $enabled")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }
}

/**
 * Assistant state
 */
enum class AssistantState {
    IDLE,
    LISTENING,
    PROCESSING,
    THINKING,
    PREPARING_SPEECH,
    SPEAKING,
    ERROR
}

private fun AssistantState.keepsScreenAwake(): Boolean =
    this == AssistantState.LISTENING ||
        this == AssistantState.PROCESSING ||
        this == AssistantState.THINKING ||
        this == AssistantState.PREPARING_SPEECH ||
        this == AssistantState.SPEAKING

data class ConversationMessage(
    val text: String,
    val isUser: Boolean
)

@Composable
private fun ConversationBubble(
    text: String,
    isUser: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = RoundedCornerShape(16.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (isUser) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                fontSize = 17.sp,
                lineHeight = 23.sp,
                textAlign = TextAlign.Start,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Assistant UI (Compose)
 */
@Composable
fun AssistantUI(
    state: AssistantState,
    messages: List<ConversationMessage> = emptyList(),
    darkTheme: Boolean = false,
    displayText: String,
    userQuery: String,
    partialText: String,
    errorMessage: String?,
    audioLevel: Float,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onInterrupt: () -> Unit = {},
    manualDictationEnabled: Boolean = false,
    manualDictationListening: Boolean = false,
    manualDictationCancelled: Boolean = false,
    canSubmitManualDictation: Boolean = false,
    onSubmitManualDictation: () -> Unit = {},
    onCancelManualDictation: () -> Unit = {},
    onPauseManualDictation: () -> Unit = {},
    onResumeManualDictation: () -> Unit = {},
    onPanelBoundsChanged: (left: Int, top: Int, right: Int, bottom: Int) -> Unit = { _, _, _, _ -> }
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(y = 10.dp)
                    .graphicsLayer {
                        scaleX = 1.04f
                        scaleY = 1.10f
                    }
                    .blur(
                        radius = 24.dp,
                        edgeTreatment = BlurredEdgeTreatment.Unbounded
                    )
                    .background(
                        color = if (darkTheme) {
                            Color.White.copy(alpha = 0.30f)
                        } else {
                            Color.Black.copy(alpha = 0.78f)
                        },
                        shape = RoundedCornerShape(24.dp)
                    )
            )
            Column(
                modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(24.dp),
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.35f),
                    spotColor = Color.Black.copy(alpha = 0.50f)
                )
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    onPanelBoundsChanged(
                        floor(bounds.left).toInt(),
                        floor(bounds.top).toInt(),
                        ceil(bounds.right).toInt(),
                        ceil(bounds.bottom).toInt()
                    )
                }
                .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            // Microphone icon
            val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
            val baseScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (state == AssistantState.LISTENING) 1.1f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "base_scale"
            )

            // Audio level animation
            // RMS dB usually ranges from roughly -2 (silence) to 10+ (loud speech).
            // We map this to a scale factor.
            // Shift -2 to 0: (level + 2)
            // Divide by expected max roughly 12: ((level + 2) / 12)
            // Clamp to 0..1 range just in case.
            val normalizedLevel = ((audioLevel + 2f) / 10f).coerceIn(0f, 1f)
            
            // Scale increases up to 1.5x for loud sounds
            val targetLevelScale = 1f + (normalizedLevel * 0.5f) 
            
            val animatedLevelScale by animateFloatAsState(
                targetValue = if (state == AssistantState.LISTENING) targetLevelScale else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow), // Slightly faster response
                label = "audio_level_scale"
            )

            // Combine breathing (baseScale) with voice reaction. 
            // When speaking loudly, the voice reaction should dominate.
            val finalScale = if (state == AssistantState.LISTENING) maxOf(baseScale, animatedLevelScale) else 1f
            val resumeDictationDescription = stringResource(R.string.manual_dictation_resume)
            val pauseDictationDescription = stringResource(R.string.manual_dictation_pause)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .offset(y = 8.dp)
                        .graphicsLayer {
                            scaleX = finalScale
                            scaleY = finalScale
                        }
                        .clip(CircleShape)
                        .background(
                            when (state) {
                                AssistantState.LISTENING -> Color(0xFF4CAF50)
                                AssistantState.SPEAKING, AssistantState.PREPARING_SPEECH -> Color(0xFF2196F3)
                                AssistantState.THINKING, AssistantState.PROCESSING -> Color(0xFFFFC107)
                                AssistantState.ERROR -> Color(0xFFF44336)
                                else -> Color(0xFF9E9E9E)
                            }
                        )
                        .then(
                            if (state == AssistantState.SPEAKING || state == AssistantState.PREPARING_SPEECH) {
                                Modifier.clickable(
                                    onClickLabel = stringResource(R.string.interrupt_description),
                                    role = Role.Button
                                ) { onInterrupt() }
                            } else if (manualDictationEnabled && manualDictationListening &&
                                (state == AssistantState.LISTENING || state == AssistantState.PROCESSING)
                            ) {
                                Modifier.clickable(
                                    onClickLabel = pauseDictationDescription,
                                    role = Role.Button
                                ) { onPauseManualDictation() }
                            } else if (manualDictationEnabled && !manualDictationListening &&
                                state == AssistantState.IDLE) {
                                Modifier.clickable(
                                    onClickLabel = resumeDictationDescription,
                                    role = Role.Button
                                ) { onResumeManualDictation() }
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (state == AssistantState.ERROR) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = (-8).dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Status text
            val statusText = if (manualDictationEnabled && state == AssistantState.IDLE) {
                if (manualDictationCancelled) null else stringResource(R.string.manual_dictation_paused)
            } else when (state) {
                    AssistantState.LISTENING -> stringResource(R.string.state_listening)
                    AssistantState.PROCESSING -> stringResource(R.string.state_processing)
                    AssistantState.THINKING -> stringResource(R.string.state_thinking)
                    AssistantState.PREPARING_SPEECH -> stringResource(R.string.preparing_speech)
                    AssistantState.SPEAKING -> stringResource(R.string.state_speaking)
                    AssistantState.ERROR -> stringResource(R.string.state_error)
                    else -> stringResource(R.string.state_ready)
                }
            if (statusText != null) {
                Text(
                    text = statusText,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val showPartialText = partialText.isNotBlank() &&
                (state == AssistantState.LISTENING ||
                    (manualDictationEnabled && state == AssistantState.IDLE))
            val spokenText = if (showPartialText) partialText else userQuery
            val hasTranscriptContent = messages.isNotEmpty() || spokenText.isNotBlank() ||
                (displayText.isNotBlank() && state != AssistantState.LISTENING) || errorMessage != null
            val transcriptScrollState = rememberScrollState()
            LaunchedEffect(messages.size, partialText, userQuery, displayText, errorMessage) {
                withFrameNanos { }
                transcriptScrollState.scrollTo(transcriptScrollState.maxValue)
            }

            if (hasTranscriptContent) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(transcriptScrollState),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    messages.forEachIndexed { index, message ->
                        if (index > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        ConversationBubble(text = message.text, isUser = message.isUser)
                    }

                    if (spokenText.isNotBlank()) {
                        if (messages.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        ConversationBubble(text = spokenText, isUser = true)
                    }

                    if (displayText.isNotBlank() && state != AssistantState.LISTENING) {
                        if (messages.isNotEmpty() || spokenText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        ConversationBubble(text = displayText, isUser = false)
                    }

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            if (manualDictationEnabled &&
                (state == AssistantState.IDLE ||
                    state == AssistantState.LISTENING ||
                    state == AssistantState.PROCESSING)
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancelManualDictation,
                        enabled = manualDictationListening || partialText.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.manual_dictation_cancel))
                    }
                    Button(
                        onClick = onSubmitManualDictation,
                        enabled = canSubmitManualDictation,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.manual_dictation_send))
                    }
                }
            }

            // Error message
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.action_try_again))
                }
            }

            }
        }
    }
}
