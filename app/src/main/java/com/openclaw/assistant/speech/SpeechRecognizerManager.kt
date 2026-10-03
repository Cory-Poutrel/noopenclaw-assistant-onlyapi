package com.openclaw.assistant.speech

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.lang.ref.WeakReference
import java.util.Locale
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Speech Recognition Manager
 */
class SpeechRecognizerManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var audioPipeRead: ParcelFileDescriptor? = null
    private var audioPipeWrite: ParcelFileDescriptor? = null
    private var audioCaptureJob: Job? = null
    private var foregroundContextRef: WeakReference<Context>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun releaseContinuousAudioSource() {
        audioCaptureJob?.cancel()
        audioCaptureJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        try {
            audioPipeWrite?.close()
        } catch (_: Exception) {
        }
        try {
            audioPipeRead?.close()
        } catch (_: Exception) {
        }
        audioPipeWrite = null
        audioPipeRead = null
    }

    fun attachForegroundContext(context: Context) {
        foregroundContextRef = WeakReference(context)
    }

    fun clearForegroundContext(context: Context? = null) {
        val current = foregroundContextRef?.get()
        if (context == null || current === context) {
            foregroundContextRef = null
        }
    }

    private fun recognitionContext(): Context {
        return foregroundContextRef?.get() ?: context
    }

    /**
     * Check if speech recognition is available
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(recognitionContext())
    }

    /**
     * Start speech recognition and return results as a Flow
     * If language is null, use system default
     */
    fun startListening(
        language: String? = null,
        silenceTimeoutMs: Long = 2500L,
        segmentedSession: Boolean = false
    ): Flow<SpeechResult> = callbackFlow {
        // Determine default language
        val targetLanguage = language ?: Locale.getDefault().toLanguageTag()
        
        android.util.Log.e("SpeechRecognizerManager", "startListening called, language=$targetLanguage, isAvailable=${isAvailable()}")

        // Always cancel and destroy the previous recognizer before creating the
        // next one. Keeping this cleanup on the main thread prevents a delayed
        // cleanup from an older flow from interfering with the new session.
        withContext(Dispatchers.Main) {
            releaseContinuousAudioSource()
            val previousRecognizer = recognizer
            if (previousRecognizer != null) {
                android.util.Log.d("SpeechRecognizerManager", "Destroying previous recognizer before recreation")
                try {
                    previousRecognizer.cancel()
                    previousRecognizer.destroy()
                } catch (e: Exception) {
                    android.util.Log.w("SpeechRecognizerManager", "Failed to destroy previous recognizer", e)
                }
                if (recognizer === previousRecognizer) {
                    recognizer = null
                }
                // Give Android's recognition service a brief opportunity to
                // release the previous binder session and microphone handle.
                delay(RECOGNIZER_RELEASE_DELAY_MS)
            }
            val recognitionContext = recognitionContext()
            if (SpeechRecognizer.isRecognitionAvailable(recognitionContext)) {
                recognizer = if (segmentedSession &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(recognitionContext)
                ) {
                    android.util.Log.d("SpeechRecognizerManager", "Creating on-device recognizer for segmented session")
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(recognitionContext)
                } else {
                    android.util.Log.d("SpeechRecognizerManager", "Creating default recognizer instance")
                    SpeechRecognizer.createSpeechRecognizer(recognitionContext)
                }
            }
        }

        if (recognizer == null) {
            android.util.Log.e("SpeechRecognizerManager", "Failed to create recognizer, sending error")
            trySend(SpeechResult.Error(context.getString(com.openclaw.assistant.R.string.error_speech_client)))
            close()
            return@callbackFlow
        }
        val currentRecognizer = recognizer!!

        android.util.Log.d("SpeechRecognizerManager", "Setting recognition listener on recognizer")
        currentRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                android.util.Log.d("SpeechRecognizerManager", "onReadyForSpeech: recognizer is ready for input")
                trySend(SpeechResult.Ready)
            }

            override fun onBeginningOfSpeech() {
                trySend(SpeechResult.Listening)
            }

            override fun onRmsChanged(rmsdB: Float) {
                trySend(SpeechResult.RmsChanged(rmsdB))
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                android.util.Log.d("SpeechRecognizerManager", "onEndOfSpeech")
                trySend(SpeechResult.Processing)
            }

            override fun onError(error: Int) {
                android.util.Log.e("SpeechRecognizerManager", "onError: code=$error")
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> context.getString(com.openclaw.assistant.R.string.error_speech_audio)
                    SpeechRecognizer.ERROR_CLIENT -> context.getString(com.openclaw.assistant.R.string.error_speech_client)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> context.getString(com.openclaw.assistant.R.string.error_speech_permissions)
                    SpeechRecognizer.ERROR_NETWORK -> context.getString(com.openclaw.assistant.R.string.error_speech_network)
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> context.getString(com.openclaw.assistant.R.string.error_speech_timeout)
                    SpeechRecognizer.ERROR_NO_MATCH -> context.getString(com.openclaw.assistant.R.string.error_speech_no_match)
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> context.getString(com.openclaw.assistant.R.string.error_speech_busy)
                    SpeechRecognizer.ERROR_SERVER -> context.getString(com.openclaw.assistant.R.string.error_speech_server)
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> context.getString(com.openclaw.assistant.R.string.error_speech_input_timeout)
                    else -> context.getString(com.openclaw.assistant.R.string.error_speech_unknown, error)
                }
                
                trySend(SpeechResult.Error(errorMessage, error))
                
                // For critical errors, force recreation of the recognizer
                // Soft errors (No Match, Timeout) can reuse the instance for speed
                val isSoftError = error == SpeechRecognizer.ERROR_NO_MATCH ||
                                  error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

                if (!isSoftError) {
                    destroy()
                }

                close()
            }

            override fun onResults(results: Bundle?) {
                android.util.Log.d("SpeechRecognizerManager", "onResults received")
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                
                if (!matches.isNullOrEmpty()) {
                    trySend(SpeechResult.Result(
                        text = matches[0],
                        confidence = confidence?.getOrNull(0) ?: 0f,
                        alternatives = matches.drop(1)
                    ))
                } else {
                    trySend(SpeechResult.Error(context.getString(com.openclaw.assistant.R.string.error_no_recognition_result), SpeechRecognizer.ERROR_NO_MATCH))
                }
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    trySend(SpeechResult.PartialResult(matches[0]))
                }
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                val matches = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidence = segmentResults.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                val bestMatch = matches?.firstOrNull()?.trim()
                if (!bestMatch.isNullOrEmpty()) {
                    trySend(
                        SpeechResult.SegmentResult(
                            text = bestMatch,
                            confidence = confidence?.getOrNull(0) ?: 0f,
                            alternatives = matches.drop(1).filter { it.isNotBlank() }
                        )
                    )
                }
            }

            override fun onEndOfSegmentedSession() {
                android.util.Log.d("SpeechRecognizerManager", "onEndOfSegmentedSession")
                trySend(SpeechResult.EndOfSegmentedSession)
                close()
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceTimeoutMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceTimeoutMs)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(
                    RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY
                )
                putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, true)
            }

            // Try hidden/unofficial extra to enforce minimum length if supported
            putExtra("android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", silenceTimeoutMs)
        }

        var continuousAudioEnabled = false
        if (segmentedSession && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val audioContext = recognitionContext()
                if (audioContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    throw SecurityException("RECORD_AUDIO permission is required for continuous dictation")
                }
                val sampleRate = 16_000
                val minimumBufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = minimumBufferSize.coerceAtLeast(sampleRate)
                val pipe = ParcelFileDescriptor.createPipe()
                val record = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = record
                    audioPipeRead = pipe[0]
                    audioPipeWrite = pipe[1]
                    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
                    intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    continuousAudioEnabled = true
                    android.util.Log.d("SpeechRecognizerManager", "Continuous microphone source prepared")
                } else {
                    record.release()
                    pipe.forEach { it.close() }
                }
            } catch (e: Exception) {
                android.util.Log.w("SpeechRecognizerManager", "Continuous microphone source unavailable", e)
                releaseContinuousAudioSource()
                intent.putExtra(
                    RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS
                )
            }
        }

        // Run on Main thread
        android.util.Log.d("SpeechRecognizerManager", "Dispatching startListening(intent) to Main thread")
        withContext(Dispatchers.Main) {
             try {
                 android.util.Log.d("SpeechRecognizerManager", "Calling recognizer.startListening()")
                 currentRecognizer.startListening(intent)
             } catch (e: Exception) {
                 android.util.Log.e("SpeechRecognizerManager", "startListening() failed", e)
                 trySend(SpeechResult.Error(context.getString(com.openclaw.assistant.R.string.error_start_failed, e.message)))
                 close()
             }
        }

        if (continuousAudioEnabled) {
            val currentAudioRecord = audioRecord
            val currentWritePipe = audioPipeWrite
            if (currentAudioRecord != null && currentWritePipe != null) {
                audioCaptureJob = launch(Dispatchers.IO) {
                    val output = ParcelFileDescriptor.AutoCloseOutputStream(currentWritePipe)
                    val buffer = ByteArray(16_000)
                    try {
                        currentAudioRecord.startRecording()
                        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                            val count = currentAudioRecord.read(buffer, 0, buffer.size)
                            if (count <= 0) break
                            output.write(buffer, 0, count)
                        }
                    } catch (e: Exception) {
                        if (kotlinx.coroutines.currentCoroutineContext().isActive) {
                            android.util.Log.w("SpeechRecognizerManager", "Continuous audio capture stopped", e)
                        }
                    } finally {
                        try {
                            output.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }

        awaitClose {
            android.util.Log.d("SpeechRecognizerManager", "awaitClose: flow closing, destroying recognizer")
            if (recognizer === currentRecognizer) {
                releaseContinuousAudioSource()
            }
            runOnMain {
                try {
                    currentRecognizer.cancel()
                    currentRecognizer.destroy()
                } catch (e: Exception) {
                    android.util.Log.w("SpeechRecognizerManager", "awaitClose cleanup failed", e)
                }
                if (recognizer === currentRecognizer) {
                    recognizer = null
                }
            }
        }
    }

    /**
     * Stop listening manually
     */
    fun stopListening() {
        val currentRecognizer = recognizer ?: return
        releaseContinuousAudioSource()
        runOnMain {
            try {
                currentRecognizer.stopListening()
            } catch (e: Exception) {
                android.util.Log.w("SpeechRecognizerManager", "stopListening failed", e)
            }
        }
    }

    /**
     * Completely destroy the recognizer resources
     */
    fun destroy() {
        val currentRecognizer = recognizer
        releaseContinuousAudioSource()
        runOnMain {
            try {
                currentRecognizer?.cancel()
                currentRecognizer?.destroy()
            } catch (e: Exception) {
                // Ignore
            }
            if (recognizer === currentRecognizer) {
                recognizer = null
            }
        }
    }

    private companion object {
        const val RECOGNIZER_RELEASE_DELAY_MS = 150L
    }
}

/**
 * Speech recognition results
 */
sealed class SpeechResult {
    object Ready : SpeechResult()
    object Listening : SpeechResult()
    object Processing : SpeechResult()
    data class RmsChanged(val rmsdB: Float) : SpeechResult()
    data class PartialResult(val text: String) : SpeechResult()
    data class Result(
        val text: String,
        val confidence: Float,
        val alternatives: List<String>
    ) : SpeechResult()
    data class SegmentResult(
        val text: String,
        val confidence: Float,
        val alternatives: List<String>
    ) : SpeechResult()
    object EndOfSegmentedSession : SpeechResult()
    data class Error(val message: String, val code: Int? = null) : SpeechResult()
}
